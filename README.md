# task-scheduler — 通用任务驱动的容器化计算调度框架

## 概述

一个轻量级的任务调度框架，采用**编排/执行分离架构**，通过状态机驱动任务全生命周期。编排层（task-orchestrator）负责任务管理和调度决策，执行层（task-worker）负责在目标机器上启动 Docker 容器执行实际计算。

适用场景：AI 模型训练、数据标注、爬虫、ETL 等需要容器化执行的批处理任务。

## 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     task-orchestrator (编排层)                       │
│                         端口: 18200                                  │
│                                                                     │
│   ┌──────────────┐    ┌─────────────┐    ┌───────────────────┐      │
│   │TaskController│───▶│ TaskService│───▶│   TaskScheduler   │      │
│   │  (REST API)  │    │ (业务逻辑)   │    │  (每5秒轮询活跃任务) │      │
│   └──────────────┘    └─────────────┘    └────────┬──────────┘      │
│                                                   │                 │
│                                          ┌────────▼──────────┐      │
│                                          │    TaskEngine     │      │
│   ┌───────────────┐                      │ (状态机驱动流转)     │      │
│   │CallbackService│◀─────────────────────│                   │      │
│   │ (完成后回调)    │                      └────────┬──────────┘      │
│   └───────────────┘                               │                 │
│                                           ┌────────▼──────────┐     │
│                                           │   WorkerClient    │     │
│                                           │ (HTTP调用执行层)    │     │
│                                           └────────┬──────────┘     │
└────────────────────────────────────────────────────┼────────────────┘
                                                     │ HTTP
                                                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      task-worker (执行层)                            │
│                         端口: 18201                                  │
│                                                                     │
│   ┌────────────────┐    ┌───────────────┐    ┌───────────────┐      │
│   │WorkerController│── ▶│ DockerService │──▶│  Docker CLI   │      │
│   │  (REST API)    │    │ (容器生命周期)  │    │ (run/inspect/ │      │
│   └────────────────┘    └───────┬───────┘    │  stop/rm)     │      │
│                                 │            └───────────────┘      │
│                         ┌───────▼───────┐                           │
│                         │   S3Service   │                           │
│                         │(数据上传/下载)  │                           │
│                         └───────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

## 模块说明

### task-orchestrator（编排层）

- **接收任务请求**：通过 REST API 接收外部创建/查询/停止任务的请求
- **配置解析**：根据 typeCode 查询 task_type 表，合并模板配置与业务参数，渲染占位符
- **调度分发**：定时轮询活跃任务，按优先级排序，控制并发上限
- **状态驱动**：通过状态机引擎驱动任务在各状态间流转
- **Worker 通信**：通过 HTTP 调用执行层的 submit/status/stop 接口
- **完成回调**：任务到达终态后，向业务方的 callbackUrl 发送通知

核心组件：

| 组件 | 职责 |
|------|------|
| TaskController | REST API 入口，提供任务 CRUD 接口 |
| TaskService | 业务逻辑层，负责任务创建、配置解析、状态查询 |
| TaskScheduler | 定时调度器，每 5 秒轮询活跃任务并异步分发给 Engine 处理 |
| TaskEngine | 状态机引擎，根据任务当前状态执行对应动作（校验/提交/查询/停止） |
| TaskStateMachine | 状态转换规则定义，校验状态流转合法性 |
| WorkerClient | HTTP 客户端，封装对执行层 REST API 的调用 |
| CallbackService | 回调服务，任务完成后通知业务方 |
| TemplateRenderer | 模板渲染器，将 `{placeholder}` 替换为实际值 |

### task-worker（执行层）

- **接收任务提交**：接收编排层发来的任务 payload（镜像、环境变量、挂载卷等）
- **数据准备**：从 S3/MinIO 下载任务所需的输入数据到本地
- **容器管理**：构建 docker run 命令，启动容器，支持 GPU、自定义网络、共享内存等
- **状态查询**：通过 docker inspect 查询容器运行状态和退出码
- **容器停止**：接收停止指令，执行 docker stop + docker rm
- **数据上传**：任务完成后将输出数据上传到 S3（如需要）

核心组件：

| 组件 | 职责 |
|------|------|
| WorkerController | REST API，提供 submit/status/stop/health 接口供编排层调用 |
| DockerService | Docker 容器生命周期管理，构建命令、启动、查询、停止、清理 |
| S3Service | 对象存储服务，负责输入数据下载和输出数据上传 |

### 两者的关系

```
编排层（1 个实例）  ──HTTP──▶  执行层（N 个实例，部署在不同机器上）
```

- 编排层通过 `task_type.jobConfig.workerUrl` 决定任务发往哪个执行层节点
- 不同任务类型可以配置不同的 workerUrl，实现任务与服务器的绑定（如 GPU 任务发 GPU 机器）
- 执行层可以部署多个实例在不同机器上，每个实例独立管理本机的 Docker 容器

## 状态流转

```
PENDING → WAITING → RUNNING → POST_PROCESSING → COMPLETED
                       ↓
                    STOPPING → STOPPED
任何非终态 → FAILED (异常时)
```

| 状态 | 含义 | Engine 动作 |
|------|------|------------|
| PENDING | 已提交，待处理 | 校验 image 和 workerUrl 配置是否完整 |
| WAITING | 配置校验通过，等待提交 | 组装 payload，调用 Worker 的 submit 接口 |
| RUNNING | 容器已启动，运行中 | 轮询 Worker 的 status 接口，更新进度 |
| POST_PROCESSING | 容器正常退出，后处理 | 标记完成 |
| COMPLETED | 已完成（终态） | 触发回调 |
| FAILED | 失败（终态） | 触发回调 |
| STOPPING | 停止中 | 调用 Worker 的 stop 接口 |
| STOPPED | 已停止（终态） | 触发回调 |

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Docker（执行层机器需要安装）

### 2. 构建

```bash
mvn clean package -DskipTests
```

构建产物：
- `task-orchestrator/target/task-orchestrator-1.0.0.jar`
- `task-worker/target/task-worker-1.0.0.jar`

### 3. 初始化数据库

```bash
mysql -u root -p < deploy/config/schema.sql
```

### 4. 启动编排层

```bash
java -jar task-orchestrator/target/task-orchestrator-1.0.0.jar
```

编排层默认监听 18200 端口，连接本地 MySQL 的 task_scheduler 数据库。

### 5. 启动执行层

在目标机器上启动（需要有 Docker 环境）：

```bash
java -jar task-worker/target/task-worker-1.0.0.jar
```

执行层默认监听 18201 端口。可以在多台机器上分别启动。

### 6. 创建任务

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
```

### 7. 查询和管理任务

```bash
# 查询单个任务
curl http://localhost:18200/api/v1/tasks/detect-anno-1-1

# 查询活跃任务列表
curl http://localhost:18200/api/v1/tasks

# 停止任务
curl -X POST http://localhost:18200/api/v1/tasks/detect-anno-1-1/stop
```

## 任务类型配置（task_type 表）

任务类型是预定义的任务模板，存储在数据库 task_type 表中。创建任务时只需指定 typeCode，系统自动从模板中获取完整配置。

### 核心字段

| 字段 | 说明 | 示例 |
|------|------|------|
| type_code | 类型编码（唯一） | `1` |
| name | 类型名称 | `目标检测AI标注` |
| identity_prefix | 任务标识前缀 | `detect-anno` |
| image_config | 镜像配置 (JSON) | `{"ImageUrl": "your-registry/ultralytics:v1"}` |
| io_config | IO 路径配置，支持 `{placeholder}` | 见下方示例 |
| env_config | 环境变量 (JSON 数组) | `[{"Name": "ENTRY", "Value": "/app/run.sh"}]` |
| resource_config | 资源需求 (JSON) | `{"Cpu": 4000, "Gpu": 100, "Memory": 32768}` |
| job_config | 执行行为配置 (JSON) | 见下方 jobConfig 说明 |
| callback_url | 默认回调地址 | `http://your-platform:8080/api/callback` |

### jobConfig 配置说明

`jobConfig` 是控制任务执行行为的核心配置：

```json
{
  "workerUrl": "http://gpu-worker-01:18201",
  "volumes": ["/opt/scripts/bootstrap.py:/opt/ml/boot/bootstrap.py:ro"],
  "command": ["python", "/opt/ml/boot/bootstrap.py"],
  "shmSize": "16g",
  "network": "host"
}
```

| 字段 | 说明 | 必填 |
|------|------|------|
| workerUrl | 执行层地址，决定任务发往哪台机器 | 是 |
| volumes | Docker 挂载卷列表 | 否 |
| command | 容器启动命令（覆盖镜像 ENTRYPOINT） | 否 |
| shmSize | 共享内存大小（覆盖全局默认值） | 否 |
| network | 网络模式（覆盖全局默认值） | 否 |

### 模板渲染

io_config 和 env_config 支持 `{placeholder}` 语法，运行时会用任务的 inputDetail 中的字段值替换：

```json
// task_type.io_config 模板
{
  "inputs": [{"s3Path": "{dataS3Path}", "localPath": "/app/inputs/"}],
  "outputs": [{"s3Path": "s3://{tenant}/{project}/job/{jobIdentity}/outputs/", "localPath": "/app/outputs/"}]
}

// 创建任务时传入的 inputDetail
{
  "tenant": "demo-dev",
  "project": "my-project",
  "dataS3Path": "s3://demo-dev-data/datasets/train/"
}

// 渲染后的实际配置
{
  "inputs": [{"s3Path": "s3://demo-dev-data/datasets/train/", "localPath": "/app/inputs/"}],
  "outputs": [{"s3Path": "s3://demo-dev/my-project/job/detect-anno-1-42/outputs/", "localPath": "/app/outputs/"}]
}
```

内置变量：`{jobIdentity}` 会自动替换为任务的 identity 值。

## 配置说明

### task-orchestrator 配置 (application.yml)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| server.port | 18200 | 编排层端口 |
| spring.datasource.url | jdbc:mysql://localhost:3306/task_scheduler | 数据库连接 |
| scheduler.poll-rate-ms | 5000 | 调度轮询间隔 (毫秒) |
| scheduler.max-concurrent-tasks | 20 | 最大并发处理任务数 |
| scheduler.task-timeout-hours | 24 | 任务超时时间 (小时) |

### task-worker 配置 (application.yml)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| server.port | 18201 | 执行层端口 |
| executor.docker.shm-size | 8g | Docker 默认共享内存大小 |
| executor.docker.network | host | Docker 默认网络模式 |
| s3.endpoint | - | S3/MinIO 服务地址 |
| s3.access-key | - | S3 访问密钥 |
| s3.secret-key | - | S3 密钥 |
| s3.region | us-east-1 | S3 区域 |

## 项目结构

```
task-scheduler/
├── task-orchestrator/                    # 编排层
│   └── src/main/java/com/example/orchestrator/
│       ├── OrchestratorApplication.java  #   启动类
│       ├── config/                       #   配置 (RestTemplate, 全局异常处理)
│       ├── controller/                   #   REST API
│       │   └── TaskController.java       #     任务接口
│       ├── engine/                       #   调度引擎
│       │   ├── TaskStateMachine.java     #     状态转换规则
│       │   ├── TaskEngine.java           #     状态分派引擎
│       │   └── TaskScheduler.java        #     定时轮询调度器
│       ├── mapper/                       #   MyBatis-Plus Mapper
│       │   ├── TaskMapper.java           #     任务表 Mapper
│       │   └── TaskTypeMapper.java       #     任务类型表 Mapper
│       ├── model/                        #   数据模型
│       │   ├── dto/                      #     请求/响应 DTO
│       │   ├── entity/                   #     数据库实体 (Task, TaskType)
│       │   └── enums/                    #     枚举 (TaskStatus)
│       └── service/                      #   业务服务
│           ├── TaskService.java          #     任务业务逻辑
│           ├── WorkerClient.java         #     Worker HTTP 客户端
│           ├── CallbackService.java      #     完成回调服务
│           └── TemplateRenderer.java     #     模板渲染器
│
├── task-worker/                          # 执行层
│   └── src/main/java/com/example/worker/
│       ├── WorkerApplication.java        #   启动类
│       ├── config/                       #   配置
│       ├── controller/                   #   REST API
│       │   └── WorkerController.java     #     Worker 接口 (submit/status/stop)
│       └── service/                      #   服务
│           ├── DockerService.java        #     Docker 容器管理
│           └── S3Service.java            #     S3 数据上传下载
│
├── deploy/                               # 部署相关
│   ├── config/schema.sql                 #   数据库建表 + 示例数据
│   ├── Dockerfile                        #   Docker 镜像构建
│   └── deploy.sh                         #   部署脚本
│
└── pom.xml                               # 父 POM (Maven 多模块)
```

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.3.10 | 应用框架 |
| MyBatis-Plus | 3.5.7 | ORM / 数据访问 |
| MySQL | 8.0+ | 任务持久化存储 |
| AWS SDK S3 | 2.31.73 | 对象存储（兼容 MinIO） |
| Docker | - | 容器化执行 |
| Java | 17 | 运行时 |

## 部署架构示例

```
                        ┌─────────────────────┐
                        │   task-orchestrator │
                        │   (编排层, 1台)       │
                        │   端口: 18200        │
                        └──────────┬──────────┘
                                   │
                 ┌─────────────────┼─────────────────┐
                 │                 │                 │
        ┌────────▼───────┐ ┌───────▼────────┐ ┌──────▼────────┐
        │  GPU Worker 01 │ │ CPU Worker 01  │ │ CPU Worker 02 │
        │  task-worker   │ │ task-worker    │ │ task-worker   │
        │  端口: 18201    │ │ 端口: 18201    │ │ 端口: 18201    │
        │                │ │                │ │               │
        │ AI训练/标注任务  │ │ 爬虫任务        │ │ ETL任务        │
        └────────────────┘ └────────────────┘ └───────────────┘
```

不同任务类型通过 `jobConfig.workerUrl` 路由到对应的 Worker 节点。
