# Jmix Application on OpenShift (Kubernetes Cluster)

Tài liệu này mô tả **toàn bộ quy trình build – deploy – scale** một ứng dụng **Jmix** lên **OpenShift** với **Hazelcast cluster**, đảm bảo chạy **nhiều pod ổn định, không mất session**.

---

## 🎯 Mục tiêu

- Deploy Jmix bằng **Deployment (không Serverless)**
- Scale nhiều pod (`replicas > 1`)
- Hazelcast embedded cluster giữa các pod
- Cache / lock / coordination của Jmix hoạt động đúng
- Quy trình **lặp lại được – không gõ tay nhiều lệnh**

---

## 🧱 Kiến trúc tổng thể

```
User
↓
OpenShift Route (nginx-proxy)
↓
Nginx Service (optional - sticky session)
↓
Jmix Service (8080 + 5701)
↓
Deployment (Jmix)
├─ Pod 1 (Hazelcast member)
├─ Pod 2 (Hazelcast member)
└─ Pod 3 (Hazelcast member)
```

- **Hazelcast chạy embedded trong mỗi pod**
- Discovery thông qua **Kubernetes API + Service**
- Không có Hazelcast Deployment riêng
- **Nginx** (optional) dùng cho sticky session

---

## 📦 Cấu trúc thư mục

```
.
├── build.gradle
├── src/main/resources
│   ├── application.properties
│   └── hazelcast.yaml
├── k8s/
│   ├── 01-deployment.yaml      # Jmix Deployment
│   ├── 02-service.yaml         # Jmix Service (8080 + 5701)
│   ├── 03-route.yaml           # OpenShift Route cho Jmix
│   ├── 04-rbac-hazelcast.yaml  # ServiceAccount + RBAC
│   ├── 05-jmix-secret.yaml     # ConfigMap (DB config, env vars)
│   └── 10-nginx-jmix-config.yaml # Nginx (optional - sticky session)
└── README.md
```

---

## ⚙️ Cấu hình Jmix + Hazelcast (CODE)

### 1️⃣ Thêm dependency Hazelcast

```gradle
dependencies {
    implementation 'com.hazelcast:hazelcast'
}
```

---

### 2️⃣ Cấu hình Hazelcast làm cache provider

**application.properties**

```properties
spring.cache.jcache.provider=com.hazelcast.cache.HazelcastMemberCachingProvider
logging.level.com.hazelcast=INFO
```

---

### 3️⃣ Cấu hình Hazelcast Kubernetes discovery

**src/main/resources/hazelcast.yaml**

```yaml
hazelcast:
  cluster-name: jmix-cluster
  network:
    port:
      port: 5701
      auto-increment: true
    join:
      multicast:
        enabled: false
      kubernetes:
        enabled: false
        service-name: jmix-app
```

> `enabled: false` để chạy local  
> Khi chạy trên OpenShift sẽ bật bằng ENV `HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true`

---

## 🐳 Build & Push Image (Docker Hub)

### Build image

```bash
./gradlew "-Pvaadin.productionMode=true" bootBuildImage
```

### Tag và push image

```bash
docker tag jmix-k8s:latest docker.io/kl3init/jmix-k8s:1.0.7
docker push docker.io/kl3init/jmix-k8s:1.0.7
```

> ⚠️ **Khuyến nghị**: Dùng tag version (`:1.0.7`, `:1.0.8`) thay vì `latest` để dễ quản lý

---

## ☸️ Deploy lên OpenShift

### 📋 Yêu cầu trước khi deploy

1. **Đã login vào OpenShift**:
   ```bash
   oc login <openshift-url>
   ```

2. **Đã tạo/chọn namespace**:
   ```bash
   oc project kl3init-dev
   # hoặc
   oc new-project kl3init-dev
   ```

3. **Đã build và push image** lên registry

---

### 🚀 Quy trình deploy LẦN ĐẦU (Setup)

#### Bước 1: Cập nhật ConfigMap với thông tin Database

Sửa file `05-jmix-secret.yaml` với thông tin database thực tế:

```yaml
data:
  MAIN_DATASOURCE_URL: jdbc:postgresql://your-db:5432/yourdb
  MAIN_DATASOURCE_USERNAME: youruser
  MAIN_DATASOURCE_PASSWORD: yourpassword
```

#### Bước 2: Cập nhật image tag trong Deployment

Sửa file `01-deployment.yaml`:

```yaml
image: docker.io/kl3init/jmix-k8s:1.0.7  # Thay bằng version mới nhất
```

#### Bước 3: Apply các file theo thứ tự

```bash
# 1. RBAC và ServiceAccount (phải apply trước)
oc apply -f k8s/04-rbac-hazelcast.yaml

# 2. ConfigMap (chứa DB config và env vars)
oc apply -f k8s/05-jmix-secret.yaml

# 3. Deployment (Jmix app)
oc apply -f k8s/01-deployment.yaml

# 4. Service (expose port 8080 và 5701)
oc apply -f k8s/02-service.yaml

# 5. Route (OpenShift route)
oc apply -f k8s/03-route.yaml

# 6. Nginx (optional - nếu dùng sticky session)
oc apply -f k8s/10-nginx-jmix-config.yaml
```

**Hoặc apply tất cả cùng lúc:**

```bash
oc apply -f k8s/
```

#### Bước 4: Kiểm tra deployment

```bash
# Kiểm tra pods
oc get pods -n kl3init-dev

# Kiểm tra services
oc get svc -n kl3init-dev

# Kiểm tra routes
oc get routes -n kl3init-dev

# Xem logs
oc logs -f deployment/jmix-app -n kl3init-dev
```

---

### 🔁 Quy trình deploy những lần SAU (Update)

#### Bước 1: Build & push image mới

```bash
./gradlew "-Pvaadin.productionMode=true" bootBuildImage
docker tag jmix-k8s:latest docker.io/kl3init/jmix-k8s:1.0.8
docker push docker.io/kl3init/jmix-k8s:1.0.8
```

#### Bước 2: Update image trong Deployment

```bash
oc set image deployment/jmix-app jmix-app=docker.io/kl3init/jmix-k8s:1.0.8 -n kl3init-dev
```

**Hoặc sửa trực tiếp trong file và apply:**

```bash
# Sửa 01-deployment.yaml với image mới
oc apply -f k8s/01-deployment.yaml
```

#### Bước 3: Restart deployment (nếu cần)

```bash
oc rollout restart deployment/jmix-app -n kl3init-dev
```

#### Bước 4: Theo dõi rollout

```bash
oc rollout status deployment/jmix-app -n kl3init-dev
```

➡️ **Rolling update – không downtime**

---

## 📄 Chi tiết Kubernetes YAML

### 🔹 01-deployment.yaml

* Chạy Jmix với Hazelcast embedded
* Sử dụng ServiceAccount `hazelcast-sa` cho RBAC
* Expose port `8080` (HTTP) và `5701` (Hazelcast)
* Có `preStop` lifecycle để shutdown êm
* Lấy env vars từ ConfigMap `jmix-config`

**Điểm quan trọng:**
- `serviceAccountName: hazelcast-sa` - Bắt buộc cho Hazelcast discovery
- `containerPort: 5701` - Bắt buộc cho Hazelcast cluster
- `preStop` - Giúp pod shutdown gracefully

---

### 🔹 02-service.yaml

Service expose 2 ports:
- **8080**: HTTP traffic cho Jmix app
- **5701**: Hazelcast cluster communication

**⚠️ BẮT BUỘC**: Service phải expose port `5701` để Hazelcast discovery hoạt động.

---

### 🔹 03-route.yaml

OpenShift Route để expose Jmix app ra ngoài.

**Lưu ý:**
- Route này trỏ trực tiếp đến Service `jmix-app`
- Nếu dùng Nginx, có thể tạo route khác trỏ đến `nginx` service

---

### 🔹 04-rbac-hazelcast.yaml

**CỰC KỲ QUAN TRỌNG** - Hazelcast cần quyền đọc Kubernetes API để discovery pod.

Bao gồm:
- **ServiceAccount**: `hazelcast-sa`
- **Role**: Quyền đọc `pods`, `services`, `endpoints`
- **RoleBinding**: Gắn Role vào ServiceAccount

**Nếu thiếu RBAC này**, Hazelcast sẽ không thể discovery các pod khác và cluster sẽ không hoạt động.

---

### 🔹 05-jmix-secret.yaml

ConfigMap chứa các biến môi trường:
- Database connection (URL, username, password)
- Spring profiles
- Hazelcast Kubernetes discovery flag

**⚠️ Lưu ý**: File này tên là "secret" nhưng thực tế là ConfigMap. Nếu cần bảo mật password, nên dùng Secret thật.

---

### 🔹 10-nginx-jmix-config.yaml (Optional)

Nginx deployment dùng cho sticky session:
- Load balance với hash cookie `JSESSIONID`
- Giúp user luôn đi vào cùng pod
- Giảm thiểu vấn đề session

**Khi nào cần:**
- Nếu không dùng Spring Session với shared cache
- Muốn đảm bảo sticky session

**Khi nào không cần:**
- Đã dùng Spring Session với Hazelcast
- Session được lưu trong shared cache

---

## 🔍 Kiểm tra sau khi deploy

### 1️⃣ Kiểm tra pods đang chạy

```bash
oc get pods -n kl3init-dev -l app=jmix-app
```

Kết quả mong đợi:
```
NAME                        READY   STATUS    RESTARTS   AGE
jmix-app-xxxxx-xxxxx       1/1     Running   0          5m
jmix-app-xxxxx-xxxxx       1/1     Running   0          5m
jmix-app-xxxxx-xxxxx       1/1     Running   0          5m
```

### 2️⃣ Kiểm tra Hazelcast cluster

```bash
oc logs deployment/jmix-app -n kl3init-dev | grep -i "Members"
```

Kết quả đúng:
```
Members {size:3, ver:3}
```

**Nếu chỉ thấy `Members {size:1}`** → Hazelcast cluster chưa hoạt động, kiểm tra:
- Service có expose port 5701?
- RBAC đã apply chưa?
- ENV `HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true`?

### 3️⃣ Kiểm tra Service

```bash
oc get svc jmix-app -n kl3init-dev -o yaml
```

Đảm bảo có 2 ports:
- `http` (8080)
- `hazelcast` (5701)

### 4️⃣ Kiểm tra Route

```bash
oc get route jmix-app -n kl3init-dev
```

Lấy URL và test:
```bash
curl https://jmix-app-kl3init-dev.apps.openshift.example.com
```

### 5️⃣ Test cluster communication

**Test Shared Cache:**
1. Login vào app
2. Scale pod: `oc scale deployment/jmix-app --replicas=2 -n kl3init-dev`
3. User không bị logout → Shared cache OK

**Test Messaging:**
1. Scale ≥ 2 pod
2. Login cùng user ở 2 browser khác nhau
3. Trigger notification (tạo entity mới)
4. Cả 2 browser đều nhận notification → Messaging OK

---

## 🔧 Troubleshooting

### ❌ Hazelcast không join cluster

**Triệu chứng**: Logs chỉ thấy `Members {size:1}`

**Nguyên nhân và cách sửa:**

1. **Thiếu RBAC**:
   ```bash
   oc apply -f k8s/04-rbac-hazelcast.yaml
   ```

2. **Service không expose port 5701**:
   ```bash
   oc get svc jmix-app -n kl3init-dev
   # Kiểm tra có port hazelcast (5701)
   ```

3. **ENV không đúng**:
   ```bash
   oc get configmap jmix-config -n kl3init-dev -o yaml
   # Kiểm tra HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true
   ```

4. **ServiceAccount không đúng**:
   ```bash
   oc get deployment jmix-app -n kl3init-dev -o yaml | grep serviceAccountName
   # Phải là hazelcast-sa
   ```

### ❌ Pod không start được

```bash
oc describe pod <pod-name> -n kl3init-dev
oc logs <pod-name> -n kl3init-dev
```

### ❌ Database connection error

Kiểm tra ConfigMap:
```bash
oc get configmap jmix-config -n kl3init-dev -o yaml
```

Sửa và apply lại:
```bash
oc apply -f k8s/05-jmix-secret.yaml
oc rollout restart deployment/jmix-app -n kl3init-dev
```

---

## ⚠️ Lưu ý quan trọng

* ❌ **KHÔNG dùng Serverless Deployment cho Jmix**
* ❌ **KHÔNG tạo Hazelcast Deployment riêng**
* ✅ Luôn có Service expose port `5701`
* ✅ Hazelcast discovery dùng Kubernetes API
* ✅ Có RBAC → nếu không sẽ bị `403 Forbidden`
* ✅ Dùng ServiceAccount riêng (`hazelcast-sa`) thay vì `default`
* ✅ ConfigMap phải có `HZ_NETWORK_JOIN_KUBERNETES_ENABLED=true`
* ✅ Namespace phải nhất quán trong tất cả file YAML

---

## 🚀 Gợi ý nâng cao (optional)

* **Readiness / Liveness probe**: Đảm bảo pod chỉ nhận traffic khi sẵn sàng
* **Resource limits**: Giới hạn CPU/memory cho pod
* **HPA (Horizontal Pod Autoscaler)**: Tự động scale dựa trên CPU/memory
* **Helm / Kustomize**: Quản lý multi-environment (dev/staging/prod)
* **Secret thay vì ConfigMap**: Bảo mật password database
* **Monitoring**: Prometheus + Grafana để monitor Hazelcast cluster

---

## 📝 Checklist deploy

Trước khi deploy, đảm bảo:

- [ ] Đã build và push image lên registry
- [ ] Đã cập nhật image tag trong `01-deployment.yaml`
- [ ] Đã cập nhật database config trong `05-jmix-secret.yaml`
- [ ] Đã login vào OpenShift và chọn đúng namespace
- [ ] Đã kiểm tra namespace trong tất cả file YAML (`kl3init-dev`)
- [ ] Đã apply RBAC trước (`04-rbac-hazelcast.yaml`)
- [ ] Sau khi deploy, kiểm tra Hazelcast cluster (`Members {size:N}`)

---

* Stack: Jmix + Spring Boot + Hazelcast  
* Platform: OpenShift / Kubernetes  
* Namespace: `kl3init-dev`
