# Docker 日志部署

服务保留控制台日志，并将文件日志写入容器内的 `/app/logs`。Compose 会把该目录挂载到部署目录的 `./logs`，因此宿主机可直接读取日志文件。

在 Linux 云服务器的项目部署目录执行以下初始化命令。目录所有者必须是容器内的 `10001:10001`，否则非 root 的服务进程没有写入权限。

```bash
mkdir -p uploads logs
sudo chown -R 10001:10001 uploads logs
```

启动或更新服务：

```bash
docker compose up -d --build
```

实时查看容器标准输出：

```bash
docker compose logs -f app
```

查看宿主机持久化日志：

```bash
tail -f logs/application.log
tail -f logs/error.log
```

`application.log` 记录全部日志，`error.log` 仅记录 ERROR 级别日志。二者均按天和每 20 MB 滚动，历史保留 30 天；压缩归档保存在 `logs/archive/`。可在服务器 `.env` 中设置 `LOG_LEVEL=DEBUG` 临时提高日志详细程度，排障结束后应恢复为 `INFO`。
