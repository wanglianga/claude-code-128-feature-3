# 牙科义齿加工订单取模返工与配送服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的义齿加工协同服务：诊所提交烤瓷冠、全瓷冠、活动义齿、隐形义齿、种植上部修复订单，技工所按牙位推进 模型接收→扫描设计→医生确认→排牙→烧结→抛光→质检→配送 八道工序，并把诊所医生、技工、质检、配送、客服、财务连接到同一颗牙/同一副义齿上，覆盖取模异常、**色号争议处理**、返工责任、加急生产、配送签收、试戴体验、订单结算与诊所月结全流程。

## 目录

- [快速开始](#快速开始)
- [测试账号](#测试账号)
- [验证方式](#验证方式)
- [API 一览](#api-一览)
- [核心业务规则](#核心业务规则)

## 快速开始

### 方式 A：Docker Compose 一键启动（推荐，无需本机装 PostgreSQL）

```bash
docker compose up --build
# 服务 http://localhost:8080 ，自动连接同容器编排的 PostgreSQL
```

启动后：健康检查 `curl http://localhost:8080/api/health`；
OpenAPI 文档：<http://localhost:8080/v3/api-docs>；Swagger UI：<http://localhost:8080/swagger-ui.html>。
也可只构建镜像：`docker build -t denture-order-service .`。

### 方式 B：本机已有 PostgreSQL（创建数据库即可）

```bash
psql -U postgres -c "CREATE DATABASE dentallab;"
psql -U postgres -c "CREATE DATABASE dentallab_test;"   # 集成测试使用
```

### 方式 C：本机没有可用实例时，用 PostgreSQL 自带工具起一个用户级实例（无需 root）

```bash
export PATH=/usr/lib/postgresql/15/bin:$PATH      # 按本机实际版本调整
initdb -D ~/pgdata -U postgres --auth=trust --encoding=UTF8
pg_ctl -D ~/pgdata -o "-p 5433 -k /tmp" -l ~/pgdata.log start
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab;"
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab_test;"
```

### 配置数据库连接（默认值对应方式 C）

| 环境变量 | 默认值 |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab` |
| `DB_USER` | `postgres` |
| `DB_PASS` | `postgres` |
| `TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab_test` |
| `SERVER_PORT` | `8080` |

### 启动服务

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/denture-order-service-1.0.0.jar
```

首次启动自动建表并写入演示数据（诊所、账号、材料批次、价格配置）。健康检查：`curl http://localhost:8080/api/health`

### 运行测试与全流程验证

```bash
mvn test          # 11 个端到端集成测试（真实 PostgreSQL，dentallab_test 库）
./verify.sh       # 需服务已启动；curl+jq 走通全流程并断言 62 项检查
```

## 测试账号

所有接口（除 `/api/auth/login`、`/api/health`）都需要请求头 `X-Auth-Token: <登录返回的token>`。

| 用户名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `doctor1` | `doctor123` | 诊所医生（晨曦口腔诊所） | 下单、确认设计、批注、记录试戴 |
| `doctor2` | `doctor123` | 诊所医生（博雅口腔门诊） | 第二家诊所 |
| `clinicadmin1` | `admin123` | 诊所管理员（晨曦） | 签收、复核返工、确认月结 |
| `receiver` | `lab123` | 技工所前台 | 接单、模型接收、发货 |
| `labadmin` | `lab123` | 技工所管理员 | 判定返工责任、库存管理 |
| `tech1` / `tech2` | `tech123` | 技工 | 扫描设计、排牙、烧结、抛光 |
| `qc1` | `qc123` | 质检员 | 质检工序 |
| `courier1` | `courier123` | 配送员 | 配送 |
| `service1` | `service123` | 客服 | 异常处理 |
| `finance1` | `finance123` | 财务 | 结算、生成月结单 |

> 演示环境使用明文密码与长期令牌，生产环境应替换为密码散列与标准认证。

## 验证方式

本项目实际执行过以下验证（结论均来自真实运行输出）：

1. **`mvn test` — 11 个集成测试全部通过**（Spring Boot 随机端口 + 真实 PostgreSQL `dentallab_test` 库）：
   - 全角色登录、错误密码 401、无令牌 401、越权 403；
   - 下单→接单→八道工序（含材料扣减、照片、预计完成时间、工序顺序校验）；
   - 发货→完好签收→自动试戴安排；牙位 11 密合 / 牙位 12 不密合→自动异常+自动返工；
   - 返工责任未判定时结算被拦截；重做后返工单自动关闭、单牙位重发、订单完成；
   - 结算金额断言（基础费 1000、难度系数 1.30、技工所承担返工扣减 200、应付 1100）；
   - 月结单未复核返工时确认被拒、复核后确认成功；
   - 加急单 + 延误签收：加急费 90、延误扣罚 15、应付 375；取模气泡异常上报与客服处理；
   - **色号争议全流程**：发起时自动调取取色照片/医生确认记录/材料批次、自然光复拍、判定重做（原件状态与费用承担单独记录、保留原义齿去向与新材料批次、返工同步质检）、牙位重做自动关闭返工单、患者试戴反馈回到同一牙位档案、医生复核关闭、质检主管复核、质检结论归档（关联异常自动解决）、医生端看板同步、结算扣减技工所承担重做费（300−200=100）。
2. **`./verify.sh` — 62 项断言全部通过**（针对 8080 端口运行中的服务，覆盖登录鉴权、下单、口扫文件、八道工序、库存扣减、在线批注触发改设计异常、发货签收、试戴、返工重做、结算拦截与金额、月结复核、按牙位看板、技工绩效、加急与配送延误场景，以及色号争议发起→证据链→复拍→重做→原件/费用/新材料批次→同步质检→试戴回写→医生关闭→质检复核归档→结算扣减）。
3. **OpenAPI / Swagger UI**：启动后 `GET /v3/api-docs` 返回 OpenAPI 3.x JSON，`GET /swagger-ui.html` 可打开交互文档（含 X-Auth-Token 鉴权头）。

## API 一览

统一响应：`{"success": true, "data": ...}`；错误：`{"success": false, "error": "..."}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录，返回 token |
| GET | `/api/auth/me` | 登录 | 当前账号 |
| POST | `/api/orders` | 诊所医生 | 提交订单（患者匿名编号、牙位、取模方式、咬合关系、色号、备注、预计试戴日期、材料要求、难度） |
| POST | `/api/orders/{id}/accept` | 前台/管理员 | 技工所接单 |
| POST | `/api/orders/{id}/rush` | 诊所/客服/管理员 | 患者加急（留痕异常事件，结算计加急费） |
| POST | `/api/orders/{id}/scan-files` | 诊所/技工所 | 登记数字口扫文件 |
| GET | `/api/orders` `/api/orders/{id}` | 登录 | 订单列表（诊所仅见本诊所）/ 详情聚合 |
| POST | `/api/orders/{id}/items/{itemId}/stages` | 技工/质检/前台 | 登记工序（操作人、材料批次与用量、照片、预计完成时间） |
| POST | `/api/orders/{id}/items/{itemId}/confirm` | 诊所医生 | 医生确认设计 |
| POST | `/api/orders/{id}/items/{itemId}/annotations` | 诊所医生 | 在线批注（设计冻结后批注自动登记“医生临时改设计”异常） |
| POST | `/api/annotations/{id}/resolve` | 技工所 | 标记批注已处理 |
| GET | `/api/orders/{id}/tooth-board` | 登录 | **按牙位看板**：设计、制作、质检、配送、试戴、返工责任 |
| POST | `/api/orders/{id}/exceptions` | 登录 | 上报异常（取模气泡/色号争议/改设计/排期拥堵/试戴不密合/加急/配送延误/返工责任不清） |
| POST | `/api/exceptions/{id}/handle` | 客服/管理员 | 处理异常 |
| GET | `/api/exceptions/open` | 登录 | 待处理异常工作台 |
| POST | `/api/orders/{id}/items/{itemId}/reworks` | 多角色 | 对单个牙位发起返工 |
| POST | `/api/reworks/{id}/assign-responsibility` | 管理员/客服 | 判定返工责任与成本（诊所/技工所/共担） |
| POST | `/api/reworks/{id}/clinic-review` | 诊所 | 复核返工（对账前置条件） |
| GET | `/api/reworks/unreviewed` | 诊所 | 本诊所待复核返工 |
| POST | `/api/orders/{id}/shade-disputes` | 诊所医生/客服 | 发起色号争议（自动调取取色照片、医生确认记录、材料批次） |
| POST | `/api/shade-disputes/{id}/natural-light-retake` | 诊所/技工所 | 自然光下复拍 |
| POST | `/api/shade-disputes/{id}/decide` | 客服/管理员 | 判定重新制作（原件状态/原义齿去向/费用承担/新材料批次）或维持原义齿 |
| POST | `/api/shade-disputes/{id}/try-in-feedback` | 诊所医生 | 患者试戴反馈（回到同一牙位档案，试戴登记也会自动回写） |
| POST | `/api/shade-disputes/{id}/doctor-close` | 诊所医生 | 医生复核后关闭 |
| POST | `/api/shade-disputes/{id}/qc-review` | 质检员/管理员 | 质检主管复核 |
| POST | `/api/shade-disputes/{id}/archive` | 质检员/管理员 | 质检结论归档（关联色号争议异常自动解决） |
| GET | `/api/shade-disputes/{id}` `/api/orders/{id}/shade-disputes` | 登录 | 色号争议详情 / 订单色号争议列表（医生端同步查看） |
| GET | `/api/shade-disputes/qc-workbench` | 质检/管理员 | 已同步质检的色号争议返工工作台 |
| GET/POST | `/api/inventory/materials` `/api/inventory/materials/{id}/batches` | 技工所 | 材料档案、批次入库、库存总览（含低库存预警） |
| POST | `/api/orders/{id}/shipments` | 前台/配送 | 发货（可只发指定牙位，支持返工后重发） |
| POST | `/api/shipments/{id}/sign` | 诊所/配送 | 签收：完好/延误/破损，直接影响试戴安排 |
| POST | `/api/orders/{id}/try-ins` | 诊所医生 | 记录试戴结果与患者体验评分 |
| POST | `/api/orders/{id}/settlement` | 财务/管理员 | 生成订单结算单 |
| GET | `/api/orders/{id}/settlement` | 登录 | 查看结算单 |
| POST | `/api/statements/generate` | 财务/管理员 | 生成诊所月结单 |
| POST | `/api/statements/{id}/confirm` | 诊所管理员 | 确认月结（账期内返工须全部复核） |
| GET | `/api/statements` | 登录 | 月结单列表 |
| GET | `/api/performance/technicians` | 管理员/财务 | 技工绩效（完成工序数、准时率、返工牙位数、材料用量） |

## 核心业务规则

- **按牙位流转**：每颗牙独立走八道工序（`MODEL_RECEIVING → SCAN_DESIGN → DOCTOR_CONFIRM → TEETH_ARRANGEMENT → SINTERING → POLISHING → QC → DELIVERY`），工序必须按序登记；每道工序记录操作人、材料批次与用量（实时扣减库存，不足报错）、照片、预计完成时间。返工只打回对应牙位，不影响整单其他牙位。
- **医生确认与批注**：扫描设计后必须由本诊所医生确认才能继续生产；设计冻结（确认）后的批注自动登记“医生临时改设计”异常。
- **异常联动**：试戴不密合自动登记异常并对该牙位自动建返工单；配送破损签收自动对整批牙位建返工单；配送延误自动登记异常并把试戴安排顺延一天；加急留痕为已解决的加急事件。
- **返工责任**：返工单责任初始为“待判定”，由技工所管理员/客服判定为诊所/技工所/双方共担并核定成本；牙位重新通过质检后返工单自动关闭。**存在责任未判定的返工时，财务无法生成结算单**。
- **色号争议处理**：患者认为义齿颜色与邻牙不一致时，诊所医生/客服发起争议，服务自动**调取取色照片、医生确认记录、材料批次**形成证据链，并可补**自然光复拍**；客服/管理员判定。判定**重新制作**时：**原件状态与费用承担单独记录**，登记**原义齿去向**（技工所留存/退回诊所/销毁/备用）与**新材料批次**，同时创建该牙位返工单并**同步质检**（返工原因类型=色号争议，费用承担写入返工单进入结算）；牙位重新走八道工序通过质检后返工单自动关闭。**患者试戴反馈回到同一牙位档案**（试戴登记自动回写），随后**医生复核关闭 → 质检主管复核 → 质检结论归档**，归档时关联色号争议异常自动解决；返工与争议在医生端订单详情、按牙位看板同步可见。判定“维持原义齿”则不产生返工，直接进入医生复核关闭。
- **结算公式**：`应付 = 基础费(单价×牙位数) × 难度系数(1+0.15×(难度-1)) + 加急费(基础费×难度系数×加急费率) + 诊所承担返工费(含色号重做诊所分摊) − 技工所承担返工费(含色号重做技工所分摊) − 配送扣罚(延误5%/破损10%)`，最低为 0。各类型单价与加急费率存于 `price_config`。
- **诊所月结**：按订单完成时间归属账期汇总已结算订单；账期内存在未结算订单时不能生成月结单；**确认月结前诊所必须复核账期内全部返工**，否则确认被拒。
- **技工绩效**：按技工统计完成工序数、准时率（对比预计完成时间）、经手牙位的返工数与材料用量。

## 工程结构

```
src/main/java/com/dentallab/
├── config/    # 拦截器、异常处理、OpenAPI、种子数据
├── model/     # 18 个实体 + 18 个枚举
├── repo/      # Spring Data JPA 仓库
├── service/   # 12 个业务服务（认证/订单/生产/异常/色号争议/返工/库存/配送/试戴/结算/月结/绩效）
└── web/       # 14 个 REST 控制器（含健康检查与 Swagger）+ 请求解析辅助
src/test/java/com/dentallab/DentalLabFlowTest.java   # 11 个端到端集成测试
verify.sh      # 62 项断言的全流程 curl 验证脚本
Dockerfile / docker-compose.yml   # 一键启动（应用 + PostgreSQL）
```
