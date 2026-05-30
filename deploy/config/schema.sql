CREATE DATABASE IF NOT EXISTS task_scheduler DEFAULT CHARACTER SET utf8mb4;
USE task_scheduler;

-- 任务类型表
CREATE TABLE IF NOT EXISTS task_type (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_code         INT          NOT NULL COMMENT '类型编码',
    name              VARCHAR(128) NOT NULL COMMENT '类型名称',
    identity_prefix   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '任务标识前缀',
    image_config      TEXT         COMMENT '镜像配置 (JSON)',
    io_config         TEXT         COMMENT 'IO配置 (JSON)',
    start_cmd_config  TEXT         COMMENT '启动命令 (JSON)',
    env_config        TEXT         COMMENT '环境变量 (JSON)',
    resource_config   TEXT         COMMENT '资源配置 (JSON)',
    job_config        TEXT         COMMENT '任务行为配置 (JSON): {workerUrl, volumes, command}',
    input_data_config TEXT         COMMENT '平台输入数据配置 (JSON)',
    callback_url      VARCHAR(512) COMMENT '任务完成后的回调地址',
    create_by         VARCHAR(64),
    create_time       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    remark            VARCHAR(512) DEFAULT '',
    status            INT          NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    UNIQUE INDEX idx_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务类型表';

-- 任务表
CREATE TABLE IF NOT EXISTS task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    identity          VARCHAR(255) NOT NULL COMMENT '任务唯一标识',
    type_code         INT          NOT NULL COMMENT '任务类型编码',
    priority          INT          NOT NULL DEFAULT 100 COMMENT '优先级, 数值越小优先级越高',
    status            INT          NOT NULL DEFAULT 0 COMMENT '任务状态: 0-待处理(PENDING) 1-预处理中(PREPARING) 2-等待资源(WAITING) 3-运行中(RUNNING) 4-后处理中(POST_PROCESSING) 5-已完成(COMPLETED) 6-失败(FAILED) 7-停止中(STOPPING) 8-已停止(STOPPED)',
    input_detail      TEXT         COMMENT '业务输入参数 (JSON)',
    transition_detail TEXT         COMMENT '状态流转明细 (JSON)',
    output_detail     TEXT         COMMENT '输出结果 (JSON)',
    executor_id       VARCHAR(255) COMMENT '执行层返回的任务标识 (如 container name)',
    progress          INT          NOT NULL DEFAULT 0 COMMENT '任务进度 0-100',
    fail_reason       TEXT         COMMENT '失败原因',
    callback_url      VARCHAR(512) COMMENT '任务完成后的回调地址, 继承自 task_type',
    callback_done     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否已回调: true-已回调 false-未回调',
    create_by         VARCHAR(64),
    duration          BIGINT       COMMENT '运行时长(分钟)',
    create_time       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    prepare_time      TIMESTAMP(3) NULL,
    waiting_time      TIMESTAMP(3) NULL,
    start_time        TIMESTAMP(3) NULL,
    post_process_time TIMESTAMP(3) NULL,
    finish_time       TIMESTAMP(3) NULL,
    update_time       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE INDEX idx_identity (identity),
    INDEX idx_status (status),
    INDEX idx_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表';

-- ============ 示例数据 ============

-- AI训练任务 → 发往 GPU 服务器
INSERT INTO task_type (type_code, name, identity_prefix, image_config, io_config, start_cmd_config, env_config, resource_config, job_config, callback_url, create_by, remark, status)
VALUES (1, '目标检测AI标注', 'detect-anno',
        '{"ImageUrl": "your-registry.example.com/ml-images/ultralytics:v20241101"}',
        '{"inputs": [{"name": "data", "s3Path": "{dataS3Path}", "localPath": "/usr/local/app/inputs/dataset/"}], "outputs": [{"name": "anno", "s3Path": "s3://{tenant}/{project}/job/{jobIdentity}/outputs/anno.jsonl", "localPath": "/usr/local/app/outputs/anno.jsonl"}]}',
        '{}',
        '[{"Name": "ENTRY", "Value": "/usr/local/app/code/train.sh"}, {"Name": "S3_ENDPOINT", "Value": "http://your-s3-endpoint:8060"}]',
        '{"Cpu": 4000, "Gpu": 100, "Memory": 32768, "GpuType": "RTX4090"}',
        '{"workerUrl": "http://gpu-worker-01:18201", "volumes": ["/opt/scripts/bootstrap.py:/opt/ml/boot/bootstrap.py:ro"], "command": ["python", "/opt/ml/boot/bootstrap.py"]}',
        'http://your-platform:8080/api/job/callback',
        'admin', 'AI训练任务，发往 GPU 服务器', 1);

-- 模型训练 → 发往同一台 GPU 服务器
INSERT INTO task_type (type_code, name, identity_prefix, image_config, io_config, start_cmd_config, env_config, resource_config, job_config, callback_url, create_by, remark, status)
VALUES (3, '目标检测模型训练', 'detect-train',
        '{"ImageUrl": "your-registry.example.com/ml-platform/ultralytics:v20250728"}',
        '{"inputs": [{"name": "data", "s3Path": "{dataS3Path}", "localPath": "/usr/local/app/inputs/dataset/"}], "outputs": [{"name": "model", "s3Path": "s3://{tenant}/{project}/job/{jobIdentity}/outputs/model/", "localPath": "/usr/local/app/outputs/model/"}]}',
        '{}',
        '[{"Name": "ENTRY", "Value": "/usr/local/app/code/train.sh"}, {"Name": "S3_ENDPOINT", "Value": "http://your-s3-endpoint:8060"}]',
        '{"Cpu": 32000, "Gpu": 400, "Memory": 32768}',
        '{"workerUrl": "http://gpu-worker-01:18201", "volumes": ["/opt/scripts/bootstrap.py:/opt/ml/boot/bootstrap.py:ro"], "command": ["python", "/opt/ml/boot/bootstrap.py"]}',
        'http://your-platform:8080/api/job/callback',
        'admin', '模型训练，发往 GPU 服务器', 1);

-- 爬虫任务 → 发往 CPU 服务器 (不需要回调)
INSERT INTO task_type (type_code, name, identity_prefix, image_config, io_config, start_cmd_config, env_config, resource_config, job_config, callback_url, create_by, remark, status)
VALUES (50, '数据爬虫', 'crawler',
        '{"ImageUrl": "python:3.11-slim"}',
        '{"inputs": [], "outputs": [{"name": "result", "s3Path": "s3://{tenant}/crawler/{jobIdentity}/outputs/", "localPath": "/app/outputs/"}]}',
        '{}',
        '[{"Name": "TARGET_URL", "Value": "{targetUrl}"}]',
        '{"Cpu": 2000, "Gpu": 0, "Memory": 4096}',
        '{"workerUrl": "http://cpu-worker-01:18201", "volumes": ["/opt/scripts/crawler.py:/app/crawler.py:ro"], "command": ["python", "/app/crawler.py"]}',
        NULL,
        'admin', '爬虫任务，发往 CPU 服务器', 1);

-- ETL 任务 → 发往另一台 CPU 服务器
INSERT INTO task_type (type_code, name, identity_prefix, image_config, io_config, start_cmd_config, env_config, resource_config, job_config, callback_url, create_by, remark, status)
VALUES (51, '数据ETL处理', 'etl',
        '{"ImageUrl": "python:3.11-slim"}',
        '{"inputs": [{"name": "source", "s3Path": "{sourceS3Path}", "localPath": "/app/inputs/"}], "outputs": [{"name": "result", "s3Path": "s3://{tenant}/etl/{jobIdentity}/outputs/", "localPath": "/app/outputs/"}]}',
        '{}',
        '[{"Name": "DB_HOST", "Value": "{dbHost}"}]',
        '{"Cpu": 4000, "Gpu": 0, "Memory": 8192}',
        '{"workerUrl": "http://cpu-worker-02:18201", "volumes": ["/opt/scripts/etl.py:/app/etl.py:ro"], "command": ["python", "/app/etl.py"]}',
        NULL,
        'admin', 'ETL任务，发往 CPU 服务器 B', 1);
