# task-scheduler — 通用任务驱动的容器化计算调度框架

## 概述

一个轻量级的任务调度框架，通过状态机驱动任务全生命周期，支持 Docker 容器化执行和本地脚本执行，可扩展对接 Kubernetes 等计算平台。

## 架构

```
                    REST API
                       │
                       ▼
              ┌─────────────────┐
              │  TaskController │  API 层
              └────────┬────────┘
                       │
              ┌────────▼────────┐
              │   TaskService   │  业务层
              └────────┬────────┘
                       │
         ┌─────────────▼──────────────┐
         │       TaskScheduler        │  定时轮询
         │  (poll active tasks)       │
         └─────────────┬──────────────┘
                       │
         ┌─────────────▼──────────────┐
         │        TaskEngine          │  状态机引擎
         │  (dispatch by status)      │
         └─────────────┬──────────────┘
                       │
         ┌─────────────▼──────────────┐
         │   TaskExecutorRegistry     │  执行器路由
         └──────┬──────────────┬──────┘
                │              │
       ┌────────▼───┐  ┌────── ▼───────┐
       │ DockerExec │  │  ScriptExec   │  可扩展
       │ (容器执行)  │  │  (脚本执行)     │
       └────────────┘  └───────────────┘
```

## 状态流转

```
PENDING → PREPARING → WAITING → RUNNING → POST_PROCESSING → COMPLETED
                                   ↓
                                STOPPING → STOPPED
任何非终态 → FAILED
```

| 状态 | 含义 | 引擎动作 |
|------|------|----------|
| PENDING | 已提交 | executor.prepare() |
| PREPARING | 预处理中 | executor.prepare() |
| WAITING | 等待资源 | executor.start() |
| RUNNING | 运行中 | executor.queryStatus() |
| POST_PROCESSING | 后处理中 | executor.postProcess() |
| STOPPING | 停止中 | executor.stop() |
| COMPLETED / FAILED / STOPPED | 终态 | 触发回调 |

## 快速开始

### 1. 构建

```bash
cd task-scheduler
mvn clean package -DskipTests
```

### 2. 启动 

```bash
java -jar target/task-scheduler-1.0.0.jar
```

### 3. 启动 (MySQL)

```bash
# 先执行建表脚本
mysql -u root -p < deploy/config/schema.sql

# 启动
java -jar target/task-scheduler-1.0.0.jar --spring.profiles.active=mysql
```

### 4. 创建任务 — 只需指定 typeCode + 业务参数

```bash
# AI训练任务 (typeCode=1，配置已在 task_type 表中预定义)
curl -X POST http://localhost:18200/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "typeCode": 1,
    "inputDetail": {
      "tenant": "demo-dev",
      "project": "my-project",
      "engineeringId": "eng-001",
      "dataS3Path": "s3://demo-dev-data/datasets/train/"
    },
    "callbackUrl": "http://my-service:8080/api/callback"
  }'

# 爬虫任务 (typeCode=50)
curl -X POST http://localhost:18200/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "typeCode": 50,
    "inputDetail": {
      "tenant": "demo-dev",
      "project": "crawler-project",
      "targetUrl": "https://example.com",
      "maxPages": "100"
    }
  }'

# 本地脚本任务 (typeCode=100，不走 Docker)
curl -X POST http://localhost:18200/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "typeCode": 100,
    "inputDetail": {
      "script": "#!/bin/bash\necho Processing...\npython3 /opt/process.py",
      "workDir": "/tmp"
    }
  }'
```

### 5. 查询任务

```bash
# 查询单个任务
curl http://localhost:18200/api/v1/tasks/detect-anno-1-1

# 查询活跃任务列表
curl http://localhost:18200/api/v1/tasks

# 查询所有任务类型
curl http://localhost:18200/api/v1/task-types

# 停止任务
curl -X POST http://localhost:18200/api/v1/tasks/detect-anno-1-1/stop
```

## jobConfig 配置说明

`task_type.jobConfig` 是控制任务执行行为的核心配置，不同任务类型通过它配置不同的脚本和命令：

```json
{
  "executorType": "docker",
  "volumes": [
    "/opt/scripts/bootstrap.py:/opt/ml/boot/bootstrap.py:ro",
    "/opt/scripts/utils.py:/app/utils.py:ro"
  ],
  "command": ["python", "/opt/ml/boot/bootstrap.py"],
  "shmSize": "16g",
  "network": "host"
}
```

| 字段 | 说明 | 示例 |
|------|------|------|
| executorType | 执行器类型 | `docker` / `script` |
| volumes | Docker 挂载卷列表 | `["/host/path:/container/path:ro"]` |
| command | 容器启动命令 | `["python", "/app/run.py"]` |
| shmSize | 共享内存大小 (覆盖全局) | `"16g"` |
| network | 网络模式 (覆盖全局) | `"host"` |

**典型场景：**

| 场景 | volumes | command |
|------|---------|---------|
| AI训练 (bootstrap.py) | `["/opt/scripts/bootstrap.py:/opt/ml/boot/bootstrap.py:ro"]` | `["python", "/opt/ml/boot/bootstrap.py"]` |
| 爬虫 (pachong.py) | `["/opt/scripts/pachong.py:/app/pachong.py:ro"]` | `["python", "/app/pachong.py"]` |
| 数据ETL (etl.py) | `["/opt/scripts/etl.py:/app/etl.py:ro"]` | `["python", "/app/etl.py"]` |
| FFmpeg转码 | 不配 | `["ffmpeg", "-i", "/input/v.mp4", "/output/v.mp4"]` |
| 镜像自带入口 | 不配 | 不配 (使用镜像 ENTRYPOINT) |

## 扩展执行器

实现 `TaskExecutor` 接口即可添加新的执行器：

```java
@Component
public class KubernetesExecutor implements TaskExecutor {
    @Override
    public String type() { return "k8s"; }

    @Override
    public ExecutionResult prepare(Task task, ResolvedTaskConfig config) { /* ... */ }

    @Override
    public ExecutionResult start(Task task, ResolvedTaskConfig config) { /* 创建 K8s Job */ }

    @Override
    public ExecutionResult queryStatus(Task task, ResolvedTaskConfig config) { /* 查询 Pod 状态 */ }

    @Override
    public ExecutionResult postProcess(Task task) { /* ... */ }

    @Override
    public void stop(Task task) { /* 删除 K8s Job */ }
}
```

创建任务时指定 `"taskType": "k8s"` 即可路由到该执行器。

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| scheduler.poll-rate-ms | 5000 | 轮询间隔(ms) |
| scheduler.max-concurrent-tasks | 10 | 最大并发任务数 |
| scheduler.task-timeout-hours | 24 | 任务超时时间(h) |
| executor.docker.shm-size | 8g | Docker 共享内存 |
| executor.docker.network | host | Docker 网络模式 |
| s3.endpoint | - | S3/MinIO 地址 |
| callback.url | - | 默认回调地址 |

## 项目结构

```
task-scheduler/
├── src/main/java/com/example/scheduler/
│   ├── TaskSchedulerApplication.java    # 启动类
│   ├── config/                          # 配置
│   ├── controller/                      # REST API
│   ├── engine/                          # 调度引擎核心
│   │   ├── TaskStateMachine.java        #   状态机
│   │   ├── TaskEngine.java              #   任务引擎
│   │   └── TaskScheduler.java           #   定时调度器
│   ├── executor/                        # 执行器抽象
│   │   ├── TaskExecutor.java            #   执行器接口
│   │   ├── ExecutionResult.java         #   执行结果
│   │   ├── TaskExecutorRegistry.java    #   执行器注册中心
│   │   ├── docker/DockerExecutor.java   #   Docker 执行器
│   │   └── script/ScriptExecutor.java   #   脚本执行器
│   ├── model/                           # 数据模型
│   ├── repository/                      # 数据访问
│   └── service/                         # 业务服务
├── deploy/                              # 部署文件
│   ├── Dockerfile
│   ├── deploy.sh
│   └── config/schema.sql
└── README.md
```
