```markdown
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
OpenShift Route
↓
Service (8080 + 5701)
↓
Deployment (Jmix)
├─ Pod 1 (Hazelcast member)
├─ Pod 2 (Hazelcast member)
└─ Pod 3 (Hazelcast member)

```

- **Hazelcast chạy embedded trong mỗi pod**
- Discovery thông qua **Kubernetes API + Service**
- Không có Hazelcast Deployment riêng

---

## 📦 Cấu trúc thư mục

```

.
├── build.gradle
├── src/main/resources
│   ├── application.properties
│   └── hazelcast.yaml
├── k8s/
│   ├── 01-deployment.yaml
│   ├── 02-service.yaml
│   ├── 03-route.yaml
│   └── 04-rbac-hazelcast.yaml
└── README.md

````

---

## ⚙️ Cấu hình Jmix + Hazelcast (CODE)

### 1️⃣ Thêm dependency Hazelcast

```gradle
dependencies {
    implementation 'com.hazelcast:hazelcast'
}
````

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
        service-name: jmix-app-service
```

> `enabled: false` để chạy local
> Khi chạy trên OpenShift sẽ bật bằng ENV

---

## 🐳 Build & Push Image (Docker Hub)

```bash
./gradlew bootBuildImage
docker tag jmix-k8s:latest kl3in/jmix-k8s:latest
docker push kl3in/jmix-k8s:latest
```

> Khuyến nghị dùng tag version (`:1.0.0`, `:1.0.1`) thay vì `latest`

---

## ☸️ Deploy lên OpenShift (1 lần)

```bash
oc apply -f k8s/
```

---

## 📄 Kubernetes YAML

### 🔹 Deployment

* Chạy Jmix
* Bật Hazelcast discovery
* Có `preStop` để shutdown êm

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jmix-app
spec:
  replicas: 3
  selector:
    matchLabels:
      app: jmix-app
  template:
    metadata:
      labels:
        app: jmix-app
    spec:
      containers:
        - name: jmix-app
          image: kl3in/jmix-k8s:latest
          ports:
            - containerPort: 8080
            - containerPort: 5701
          env:
            - name: HZ_NETWORK_JOIN_KUBERNETES_ENABLED
              value: "true"
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
```

---

### 🔹 Service (BẮT BUỘC cho Hazelcast)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: jmix-app-service
spec:
  selector:
    app: jmix-app
  ports:
    - name: http
      port: 8080
      targetPort: 8080
    - name: hazelcast
      port: 5701
      targetPort: 5701
```

---

### 🔹 Route (OpenShift)

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: jmix-app
spec:
  to:
    kind: Service
    name: jmix-app-service
  port:
    targetPort: http
  tls:
    termination: edge
```

---

### 🔹 RBAC cho Hazelcast (CỰC KỲ QUAN TRỌNG)

Hazelcast cần quyền đọc Kubernetes API để discovery pod.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: hazelcast-role
rules:
- apiGroups: [""]
  resources:
    - pods
    - services
    - endpoints
  verbs:
    - get
    - list
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: hazelcast-rolebinding
subjects:
- kind: ServiceAccount
  name: default
roleRef:
  kind: Role
  name: hazelcast-role
  apiGroup: rbac.authorization.k8s.io
```

> RBAC này **chỉ cần apply 1 lần / namespace**

---

## 🔁 Quy trình deploy những lần SAU

### 1️⃣ Build & push image mới

```bash
./gradlew "-Pvaadin.productionMode=true" bootBuildImage
docker tag kl3in/jmix-k8s:latest kl3init/jmix-k8s:1.0.1
docker push kl3init/jmix-k8s:1.0.1
```

### 2️⃣ Update image trong Deployment

```bash
oc set image deployment/jmix-k8s jmix-k8s=docker.io/kl3init/jmix-k8s:1.0.1
oc rollout restart deployment jmix-k8s

```

### 3️⃣ Theo dõi rollout

```bash
oc rollout status deployment/jmix-app
```

➡️ **Rolling update – không downtime**

---

## 🔍 Kiểm tra Hazelcast cluster

```bash
oc logs jmix-app-xxxxx | grep -i hazelcast
```

Kết quả đúng:

```
Members {size:3, ver:3}
```

---

## ⚠️ Lưu ý quan trọng

* ❌ **KHÔNG dùng Serverless Deployment cho Jmix**
* ❌ **KHÔNG tạo Hazelcast Deployment riêng**
* ✅ Luôn có Service expose port `5701`
* ✅ Hazelcast discovery dùng Kubernetes API
* ✅ Có RBAC → nếu không sẽ bị `403 Forbidden`

---

## 🚀 Gợi ý nâng cao (optional)

* Readiness / Liveness probe
* Sticky session hoặc Spring Session
* HPA (Auto scale)
* Helm / Kustomize cho multi-env

---
* Stack: Jmix + Spring Boot + Hazelcast
* Platform: OpenShift / Kubernetes

