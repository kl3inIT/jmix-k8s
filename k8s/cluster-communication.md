# Jmix Cluster Communication & Messaging

Tài liệu này mô tả **cách các node Jmix giao tiếp với nhau trong môi trường cluster**, các **cơ chế nội bộ**, **công nghệ nền**, và **cách kiểm tra khi deploy trên OpenShift/Kubernetes**.

---

## 1. Tổng quan Cluster Communication trong Jmix

Khi một ứng dụng Jmix chạy với **nhiều instance (pod)**, framework cần đảm bảo:

* Dữ liệu cache **nhất quán**
* Lock phân tán **hoạt động đúng**
* Các sự kiện (entity change, notification…) **được broadcast**
* Người dùng **không bị lỗi khi request đi vào pod khác**

Để làm được điều này, Jmix sử dụng **2 cơ chế giao tiếp độc lập**:

```
┌──────────────────────────────────┐
│          JMIX CLUSTER             │
├──────────────────────────────────┤
│ 1. Shared Cache (State sharing)   │
│ 2. Messaging (Event broadcasting) │
└──────────────────────────────────┘
              ↓
         Hazelcast (default)
```

> 🔑 **Hazelcast là implementation mặc định cho cả 2 cơ chế**

---

## 2. Shared Cache (Chia sẻ trạng thái – STATE)

### 2.1 Shared Cache dùng để làm gì?

Shared Cache được dùng cho các **dữ liệu cần nhất quán giữa các node**:

| Jmix subsystem      | Mục đích                |
| ------------------- | ----------------------- |
| Query Cache         | Cache kết quả truy vấn  |
| Security            | Cache role & permission |
| Pessimistic Locking | Lock phân tán           |
| Dynamic Attributes  | Cấu hình runtime        |

➡️ Nếu **không có shared cache**, mỗi pod sẽ:

* query DB riêng
* lock riêng
* cache riêng
  → **cluster sẽ sai logic**

---

### 2.2 Cách Jmix implement Shared Cache

Luồng kỹ thuật:

```
Jmix Feature
   ↓
Spring Cache abstraction
   ↓
JCache (JSR-107)
   ↓
Hazelcast (IMap)
```

Cấu hình bắt buộc trong app:

```properties
spring.cache.jcache.provider=
  com.hazelcast.cache.HazelcastMemberCachingProvider
```

Và dependency:

```gradle
implementation 'com.hazelcast:hazelcast'
```

---

### 2.3 Ví dụ thực tế (Security cache)

```
Admin thay đổi role ở Pod A
   ↓
Role cache update (Hazelcast)
   ↓
Pod B, C nhận ngay
   ↓
User không cần logout/login lại
```

➡️ Đây là **state sharing**, không phải event.

---

## 3. Messaging (Phát sự kiện – EVENT)

### 3.1 Messaging dùng để làm gì?

Messaging dùng cho các **sự kiện cần broadcast**, không phải state lâu dài.

Các feature dùng messaging:

| Jmix subsystem | Mục đích                  |
| -------------- | ------------------------- |
| Entity Cache   | Thông báo entity thay đổi |
| Notifications  | Push notification         |

---

### 3.2 Cách Jmix implement Messaging

Luồng kỹ thuật:

```
Jmix Feature
   ↓
Spring Messaging (SubscribableChannel)
   ↓
Hazelcast ITopic
```

* Mỗi node **subscribe** vào topic
* Một node **publish**
* Tất cả node còn lại **nhận event**

➡️ **Không lưu state**, chỉ gửi tín hiệu

---

### 3.3 Ví dụ: Notifications

```java
notificationManager.createNotification()
    .withSubject("New order")
    .withRecipientUsernames("admin")
    .toChannelsByNames(InAppNotificationChannel.NAME)
    .send();
```

Luồng thực tế:

```
Order được tạo ở Pod A
   ↓
Notification event publish
   ↓
Hazelcast topic
   ↓
Pod B, C nhận event
   ↓
User thấy notification (dù login ở pod nào)
```

---

## 4. So sánh Shared Cache vs Messaging (RẤT QUAN TRỌNG)

| Tiêu chí        | Shared Cache | Messaging        |
| --------------- | ------------ | ---------------- |
| Bản chất        | State        | Event            |
| Lưu dữ liệu     | Có           | Không            |
| Công nghệ       | JCache       | Spring Messaging |
| Hazelcast       | IMap         | ITopic           |
| Dùng cho        | Cache, lock  | Broadcast        |
| Mất là lỗi nặng | ❌            | ⚠️               |

> 🔴 **Shared Cache là bắt buộc**
> 🟡 Messaging quan trọng nhưng không giữ state

---

## 5. Hazelcast trong Jmix Cluster

### 5.1 Mô hình triển khai

* Hazelcast **embedded trong mỗi pod**
* Mỗi pod = 1 Hazelcast member
* **Không có Hazelcast Deployment riêng**

```
Pod 1 (Jmix + Hazelcast)
Pod 2 (Jmix + Hazelcast)
Pod 3 (Jmix + Hazelcast)
        ↓
   Hazelcast Cluster
```

---

### 5.2 Kubernetes Discovery

Hazelcast dùng **Kubernetes API** để:

* Liệt kê pod
* Tìm pod cùng service
* Join cluster

Yêu cầu:

* Service expose port `5701`
* ENV:

```yaml
HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true
```

* RBAC cho ServiceAccount:

```yaml
resources: [pods, services, endpoints]
verbs: [get, list]
```

---

## 6. Kiểm tra Cluster Communication trên OpenShift

### 6.1 Kiểm tra Hazelcast cluster

```bash
oc logs jmix-app-xxxxx | grep Members
```

Kết quả đúng:

```
Members {size:3}
```

---

### 6.2 Test Shared Cache

**Security / Session test**:

* Login 1 user
* Scale pod up/down
* Không bị logout → Shared cache OK

**Pessimistic lock test**:

* 2 user mở cùng record
* Một user bị lock → OK

---

### 6.3 Test Messaging (CHUẨN NHẤT)

1. Scale ≥ 2 pod
2. Login cùng user ở 2 browser khác nhau
3. Trigger notification (entity created)

➡️ **Cả 2 browser đều nhận notification**

→ Messaging cluster hoạt động

---

## 7. Những thứ KHÔNG thuộc Cluster Communication

* ❌ HTTP session routing (Ingress / sticky session)
* ❌ Load balancer
* ❌ Database replication

> Jmix cluster **không thay thế** các thành phần hạ tầng này

---

## 8. Kết luận

* Jmix cluster dùng **2 cơ chế giao tiếp**

    * Shared Cache (state)
    * Messaging (event)
* Hazelcast là implementation mặc định
* Kubernetes chịu trách nhiệm discovery
* Nếu Hazelcast cluster OK → Jmix cluster OK

---

## 9. Checklist nhanh (Troubleshooting)

* [ ] Hazelcast Members > 1
* [ ] Service có port 5701
* [ ] RBAC không bị 403
* [ ] `HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true`
* [ ] Notification broadcast được

---
oc set env deployment/jmix-app \
MAIN_DATASOURCE_URL="$(oc get configmap jmix-secret -n kl3init-dev -o jsonpath='{.data.MAIN_DATASOURCE_URL}')" \
MAIN_DATASOURCE_USERNAME="$(oc get configmap jmix-secret -n kl3init-dev -o jsonpath='{.data.MAIN_DATASOURCE_USERNAME}')" \
MAIN_DATASOURCE_PASSWORD="$(oc get configmap jmix-secret -n kl3init-dev -o jsonpath='{.data.MAIN_DATASOURCE_PASSWORD}')" \
SPRING_PROFILES_ACTIVE=k8s \
HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true \
-n kl3init-dev

oc patch service jmix-app \
-p '{
"spec": {
"ports": [
{
"name": "http",
"port": 8080,
"targetPort": 8080
},
{
"name": "hazelcast",
"port": 5701,
"targetPort": 5701
}
]
}
}' \
-n kl3init-dev

oc patch deployment jmix-app \
-p '{"spec":{"template":{"spec":{"serviceAccountName":"hazelcast-sa"}}}}' \
-n kl3init-dev