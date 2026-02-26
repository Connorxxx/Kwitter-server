# Ktor VPS 一键部署指南（Caddy + acme.sh + Cloudflare）

本目录用于下面这条流程：

1. 本地打包 `app.jar`
2. 上传 `deploy` 目录到 VPS
3. acme.sh 通过 Cloudflare DNS API 签发证书
4. Caddy 加载证书并反代到 Ktor
5. acme.sh 自动续签，续签后自动 reload Caddy

---

## 0. 先做安全处理

如果你把 Cloudflare Token 明文发到聊天或截图里，请立即在 Cloudflare 控制台撤销（Revoke）该 token，并创建新 token。

---

## 1. 准备条件

1. 你有一台 VPS（建议 Ubuntu 22.04+）。
2. VPS 已安装 Docker + Compose 插件（`docker compose version` 可执行）。
3. 你有域名 `k.torbox.uk` 并可管理 Cloudflare DNS。
4. VPS 安全组/防火墙放行：
- `443/tcp`
- `443/udp`（可选，HTTP/3）
5. 本方案是 `443-only`，适合你现在这种 `80` 已被 Nginx 占用的场景。

---

## 2. 本地打包 fat jar

在项目根目录执行：

- macOS/Linux:

```bash
./gradlew buildFatJar
```

- Windows PowerShell:

```powershell
.\gradlew.bat buildFatJar
```

把 `build/libs/K man-all.jar` 复制到 `deploy` 目录并改名为 `app.jar`。

---

## 3. 配置应用环境变量（`.env`）

在 `deploy` 目录：

```bash
cp .env.example .env
```

示例：

```env
DOMAIN=k.torbox.uk

POSTGRES_DB=twitter_db
POSTGRES_USER=myuser
POSTGRES_PASSWORD=replace-with-strong-password

JWT_AUDIENCE=jwt-audience
JWT_REALM=kman-api
JWT_SECRET=replace-with-long-random-secret
```

说明：

1. `DOMAIN` 只填域名，不要带 `https://`。
2. `POSTGRES_PASSWORD` 必改，建议 16 位以上。
3. `JWT_SECRET` 必改，建议 32 位以上随机字符串。

---

## 4. 配置 Cloudflare DNS

为 `k.torbox.uk` 增加 `A` 记录指向 VPS 公网 IP，TTL 默认即可。

在 Cloudflare 控制台同时确认：

1. `SSL/TLS` 模式设置为 `Full (strict)`。
2. 若刚开始排障，可以先关闭 CDN 代理（灰云）验证回源；稳定后再开启（橙云）。
3. 如果你保留 Nginx 在 `80` 端口，可在 Nginx 配置里把 HTTP 301 跳转到 HTTPS。

---

## 5. 上传文件到 VPS

示例上传到 `/opt/kman`：

```bash
scp -r ./deploy/* user@your-vps-ip:/opt/kman/
scp ./deploy/.env user@your-vps-ip:/opt/kman/.env
```

上传后，VPS 上目录应包含：

1. `/opt/kman/app.jar`
2. `/opt/kman/docker-compose.yml`
3. `/opt/kman/Caddyfile`
4. `/opt/kman/.env`

---

## 6. 安装 acme.sh（在 VPS）

```bash
curl https://get.acme.sh | sh -s email=you@example.com
source ~/.bashrc
```

---

## 7. 配置 Cloudflare API 凭据（在 VPS）

创建仅本次 shell 使用的环境变量（不要写进公开仓库）：

```bash
export CF_Token="你的新Token"
export CF_Account_ID="你的Account_ID"
export CF_Zone_ID="你的Zone_ID"
```

Cloudflare token 最小权限建议（按 acme.sh Cloudflare 文档）：

1. `Zone:DNS:Edit`
2. `Zone:Zone:Read`

---

## 8. 申请证书（DNS-01）

```bash
~/.acme.sh/acme.sh --issue --dns dns_cf -d k.torbox.uk
```

如果签发成功，继续安装证书到部署目录。

---

## 9. 安装证书到 Caddy 挂载目录 + 配置自动 reload

```bash
mkdir -p /opt/kman/certs

~/.acme.sh/acme.sh --install-cert -d k.torbox.uk \
  --key-file /opt/kman/certs/privkey.pem \
  --fullchain-file /opt/kman/certs/fullchain.pem \
  --reloadcmd "docker exec kman_caddy caddy reload --config /etc/caddy/Caddyfile || docker restart kman_caddy"
```

说明：

1. `acme.sh` 会自动创建续签任务（cron）。
2. 续签后会执行上面的 `reloadcmd`，让 Caddy 重新加载新证书。

---

## 10. 启动服务（在 VPS）

```bash
cd /opt/kman
docker compose up -d
```

---

## 11. 验证 HTTPS 与服务状态

1. 查看容器：

```bash
docker compose ps
```

2. 查看 Caddy 日志：

```bash
docker compose logs -f caddy
```

3. 查看应用日志：

```bash
docker compose logs -f app
```

4. 浏览器访问：

`https://k.torbox.uk`

---

## 12. 日志查看（宿主机文件）

应用日志映射到宿主机：

1. `/opt/kman/logs/application.log`
2. `/opt/kman/logs/application-YYYY-MM-DD.log`

实时查看：

```bash
tail -f /opt/kman/logs/application.log
```

---

## 13. 常用运维命令

1. 重启全部服务：

```bash
docker compose restart
```

2. 仅重启应用：

```bash
docker compose restart app
```

3. 停止并删除容器（保留目录数据）：

```bash
docker compose down
```

4. 查看数据库日志：

```bash
docker compose logs -f db
```

---

## 14. 常见问题排查

1. `acme.sh --issue` 失败
- 检查 `CF_Token/CF_Zone_ID` 是否正确。
- 检查 token 权限是否包含 `Zone:DNS:Edit` 和 `Zone:Zone:Read`。
- 检查该域名是否确实托管在当前 Cloudflare 账户中。

2. Caddy 报证书文件不存在
- 确认 `/opt/kman/certs/fullchain.pem` 和 `/opt/kman/certs/privkey.pem` 已存在。
- 先执行第 8、9 步，再启动 compose。

3. HTTPS 打不开
- 检查 DNS 是否已生效到 VPS。
- 检查 443 端口放行。
- 查看 `docker compose logs -f caddy`。

4. 应用连不上数据库
- 检查 `.env` 的 `POSTGRES_*`。
- `docker compose ps` 确认 `db` healthy。

5. `The "... variable is not set"` 警告
- 确认文件名是 `.env`，不是 `.evn`。
- 确认你在 `/opt/kman` 目录执行 `docker compose`。
- 可显式指定：`docker compose --env-file .env up -d`。
