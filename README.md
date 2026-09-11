# 校园报修异步提交页

基于 **Jakarta Servlet + 原生 fetch** 的校园报修单在线提交页面。用户选择宿舍 / 教学楼 /
食堂，填写具体位置、问题描述与联系方式后异步提交，成功返回工单号；页面具备加载、成功、
失败三种状态，并对错误类型做了区分。

## 技术栈

- 后端：Jakarta Servlet 6.0（`jakarta.servlet.*`），JDK 11+
- 容器：Apache Tomcat 10.1+（或 Jetty 11+），无需任何额外 JSON 依赖
- 前端：HTML + CSS + 原生 JavaScript（fetch / URLSearchParams）

## 构建与运行

```bash
# 1. 打包（需要 Maven 与 JDK 11+）
mvn clean package

# 2. 部署 target/campus-repair.war 到 Tomcat 10.1 的 webapps/
cp target/campus-repair.war $TOMCAT_HOME/webapps/

# 3. 启动后访问
#    http://localhost:8080/campus-repair/
```

> 注意：使用的是 Jakarta EE 9+ 命名空间（`jakarta.*`），**不支持 Tomcat 9 及以下**。

## 接口约定

`POST /campus-repair/api/repair`，`Content-Type: application/x-www-form-urlencoded`

| 字段        | 说明                                       | 必填 |
|-------------|--------------------------------------------|------|
| `location`  | 地点分类：`dorm` / `teaching` / `canteen`  | 是   |
| `building`  | 具体位置（如 7 号楼 302），≤100 字         | 是   |
| `description` | 问题描述，≤500 字                        | 是   |
| `contact`   | 联系方式，≤50 字                           | 是   |

### 成功响应（HTTP 200）

```json
{
  "code": "OK",
  "message": "报修成功，后勤师傅将尽快上门处理。",
  "ticketNo": "WX20260910153012483"
}
```

### 错误响应（三类明确区分）

| HTTP 状态 | code                | 触发场景                         |
|-----------|---------------------|----------------------------------|
| 400       | `MISSING_FIELD`     | 必填字段缺失或为空               |
| 400       | `INVALID_FIELD`     | location 非法 / 字段超长         |
| 405       | `METHOD_NOT_ALLOWED`| 用 GET 等非 POST 方法访问接口    |
| 503       | `SERVER_BUSY`       | 服务器忙（带 `Retry-After: 5`）  |

错误响应示例：

```json
{ "code": "MISSING_FIELD", "message": "请填写问题描述。", "field": "description" }
```

> 503 判定：服务端使用有界工单受理通道（`TicketAcceptor`，2 个工作线程 +
> 容量 100 的有界队列），**仅在下游处理能力真实饱和（受理队列已满）或服务正在
> 关闭时**才返回 503 `SERVER_BUSY` 并携带 `Retry-After: 5`；正常连续提交不会被
> 人为拒绝，每个合法请求都会获得工单号。接生产环境时，将
> `TicketAcceptor#dispatch` 替换为真实的落库 / 派单调用即可，队列满、下游超时等
> 仍会如实反馈为 503。

## 前端状态说明

- **加载中**：提交按钮禁用并显示旋转图标，状态区显示“正在提交报修单…”，禁止重复提交。
- **成功**：绿色面板展示工单号（支持一键复制），可点击“再报一单”重置表单。
- **失败**：红色面板展示原因，并按错误类型给出不同处理：
  - `MISSING_FIELD` / `INVALID_FIELD`：高亮对应输入框并聚焦；
  - `SERVER_BUSY`：保留已填表单，按响应头 `Retry-After` 在重试按钮上倒计时，
    倒计时结束后可直接重新提交（该次提交未生成工单号）；
  - `METHOD_NOT_ALLOWED`：提示请求方式不对；
  - 网络异常 / 响应无法解析：单独提示网络问题。

服务端返回的文本一律通过 `textContent` 渲染，避免 XSS。

## curl 快速验证

```bash
BASE=http://localhost:8080/campus-repair/api/repair

# 成功（200；只有下游真实饱和/停机时才会得到 503，可连续提交任意次）
curl -i -X POST "$BASE" \
  --data-urlencode "location=dorm" \
  --data-urlencode "building=7号楼302" \
  --data-urlencode "description=水龙头漏水" \
  --data-urlencode "contact=13800000000"

# 字段缺失（400 MISSING_FIELD）
curl -i -X POST "$BASE" --data "location=dorm&building=7-302&contact=138"

# 字段非法（400 INVALID_FIELD）
curl -i -X POST "$BASE" --data "location=library&building=x&description=y&contact=z"

# 方法不对（405 METHOD_NOT_ALLOWED）
curl -i "$BASE"

# 服务器忙（503 SERVER_BUSY）——仅当下游受理能力真实饱和或服务停机时出现
```
