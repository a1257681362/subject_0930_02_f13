# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 海水养殖台账（aqua-1）

模块包路径 `com.evops.aqua`，已打通：建池 → 投苗 → 日常记录 → 饲料/用药 → 抽检 → 换池（支持分池）→ 出池。成本核算见 aqua-10。

- 养殖池档案：水深、盐度、所属海区（`POST /api/aqua/ponds`）
- 投苗批次：苗种来源、入池数量、计划出池日；批次编号全局唯一（`POST /api/aqua/batches`）
- 饲料、用药、日常、抽检：按池按批留痕（`/api/aqua/records/**`），批次不在该池或已出池会被拒绝
- 换池：`POST /api/aqua/batches/{id}/transfer` 自动生成转移记录并更新批次所在池
- 出池：`POST /api/aqua/batches/{id}/harvest` 生成出池记录，批次封存
- 原始投苗单不可删除：无 DELETE 接口，表上无删除标记
- 批次全链路追溯：`GET /api/aqua/batches/{id}/trace`

接口需先登录：`POST /api/auth/login`（bootstrap / bootstrap）。

验收：`mvn test -Dtest=AquaLedgerAcceptanceTest`，从养殖池档案入口追到投苗批次落库，逐检查点输出报告，失败即停并给出停点。

## 水质采样计划与缺测补录（aqua-2）

模块包路径 `com.evops.aqua.wq`，在台账之上打通：分阶段规则版本 → 采样计划 → 按池生成任务 → 草稿/提交 → 缺测补录 → 主管审核 → 池塘健康曲线，全程留证。

- 指标规则版本化（`POST /api/aqua/wq/rule-versions`）：不同养殖阶段配置采样频率（间隔天数）与指标正常范围；新版本发布后旧版本自动停用，**新版本只作用于未开始任务**——在执行计划的版本指针切换，从未录入过的草稿任务按新版本重建指标快照；已暂存/已提交/已审核任务冻结旧版本不变。
- 采样计划（`/api/aqua/wq/plans`）：绑定规则版本与养殖阶段、覆盖日期区间与池塘；`POST /{id}/tasks/generate` **按池按频率生成任务**，同计划同池同日幂等，重复请求不产生第二条结果。
- 采样执行（`/api/aqua/wq/tasks/{id}`）：`PUT /draft` 可只录部分指标暂存草稿；`POST /submit` 提交时每个指标必须有值，**缺测项必须说明原因**，否则拒绝；重复提交返回同一任务。
- 缺测/历史补录（`POST /api/aqua/wq/backfills`，requestId 强制幂等）：**只能追加一行，绝不覆盖原采样值**；重复补录请求返回首次记录。
- 主管审核（`POST /api/aqua/wq/tasks/{id}/review`）：驳回必须填意见且退回草稿；**审核通过后已测值才进入池塘健康曲线**（`GET /api/aqua/wq/ponds/{pondId}/health-curve`，缺测项不入曲线，取原值不取补录值）；重复审核不重复写曲线。
- 所有修改保留证据：生成/暂存/提交/补录/驳回/通过/版本切换均写 `t_wq_change_evidence`（含操作前后快照与幂等请求号），可经 `GET /api/aqua/wq/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=WaterQualitySamplingAcceptanceTest`，14 个检查点逐段输出报告，失败即停并给出停点。

## 饲料批号与配方投喂（aqua-3）

模块包路径 `com.evops.aqua.feed`，在台账之上打通：饲料批号三量账 → 配方按苗种/日龄/水温版本化 → 投喂单准备即冻结配方快照 → 锁定占用批号 → 临时减量审批 → 执行转消耗，日汇总试喂与正式分列，全程留证。

- 饲料批号（`POST /api/aqua/feed/batches`）：一批一行三量账（入库 / 锁定占用 / 已消耗），可用量=入库−锁定−已消耗；批号不可删除。锁定时按 **FEFO 先到期先用** 从未过期批号占用，可跨批号拼配；**批号已过期或可用库存不足一律拦截，且不残留半占用**（同事务回滚）。
- 投喂配方版本（`POST /api/aqua/feed/formulas`）：按苗种发布，版本下以「日龄闭区间 + 水温闭区间」配置饲料与计划投喂量，命中多行取区间最窄（最具体）者。**配方调整发布新版本，同苗种旧版本自动停用**；投喂单建立即冻结当时版本指针与计划量快照，**历史投喂永远引用旧版本快照，不受新版本影响**。
- 投喂单（`/api/aqua/feed/orders`）：`POST /prepare` 按池按批按日，日龄由投苗日与投喂日推算（不由前端填写），区分 `FORMAL` 正式投喂 / `TRIAL` 试喂，同池同批同日同类型幂等；`POST /{id}/lock` 按 FEFO 占用批号；**`POST /{id}/execute` 只有锁定后才允许执行**（草稿态直接执行拒绝），执行把锁定占用转为批号实际消耗，重复执行幂等；`POST /{id}/unlock` 释放占用退回草稿。
- 临时减量（`POST /api/aqua/feed/orders/{id}/items/{itemId}/reduction`）：仅锁定态可登记，实际量必须小于锁定量，**必须登记减量原因与审批人**，登记后释放差额占用，执行按减量后实际量消耗。
- 日汇总（`GET /api/aqua/feed/orders/daily-summary`）：按池按日只统计已执行投喂，**试喂与正式投喂严格分列、绝不混算**。
- 海水养殖管理权限（`/api/aqua/feed/access`）：`POST /tighten` 收紧、`POST /relax` 放开（均须登记原因）。**收紧后饲料批号停止推进**——新批号入库、锁定占用、执行消耗一律拦截；**已确认的配方版本与历史投喂事实保留不变**。
- 全程留证：入库/锁定/解锁/减量/执行/版本停用/权限收紧放开均写 `t_feed_change_evidence`（含操作前后 JSON 快照与幂等请求号），可经 `GET /api/aqua/feed/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=FeedFormulaFeedingAcceptanceTest`，17 个检查点逐段输出报告，失败即停并给出停点。

## 疫病观察与隔离池调度（aqua-4）

模块包路径 `com.evops.aqua.disease`，在台账之上打通：登记隔离池档案 → 发现异常症状生成观察单 → 按风险等级推荐隔离池 → 调度前校验 → 调入隔离池 → 兽医复核解除回原池，数量逐笔记录，定时复查任务幂等，全程留证且不丢操作者。

- 池塘隔离调度档案（`/api/aqua/disease/pond-guards`）：按池登记隔离池角色、容量、消毒有效期、调入权限，操作者必填，变更留证。
- 疫病观察单（`POST /api/aqua/disease/observations`，requestId 强制幂等）：发现异常症状即生成，记录症状、风险等级（LOW/MEDIUM/HIGH）与观察人；重复提交返回同一单。
- 按风险等级推荐隔离池（`GET /api/aqua/disease/observations/{id}/recommend-pools`）：低风险建议原地观察；中风险仅推荐同海区隔离池；高风险推荐全部可用隔离池（同海区优先）；被排除候选逐条说明原因。
- 隔离调度（`POST /api/aqua/disease/observations/{id}/dispatch`，requestId 幂等）：**转移前校验池容量、消毒状态、目标池调入权限与混养占用，失败时一次性说明全部冲突原因**并留失败证据（独立事务，不随业务回滚）；成功生成隔离单并同步台账转移记录。
- 隔离中管控：批次**禁止换池（混养）与出池**（台账接口直接拦截）；隔离池在隔离期间**禁止投苗混养**，他批不得再调入同一隔离池。
- 解除隔离（`POST /api/aqua/disease/isolations/{id}/release`）：**必须由兽医复核**——复核兽医与复核意见缺一不可；解除后批次回原池，重复解除幂等不多写。
- 数量逐笔账（`GET /api/aqua/disease/isolations/movements`）：原池与隔离池之间调入、回池各记一笔，数量与操作者随行，只追加。
- 定时复查任务（Quartz，cron 由 `aqua.disease.followup-scan-cron` 配置，默认每日 02:10）：按日扫描未结观察单生成复查任务，**task_no 按观察单+日期唯一——应用重启、任务重跑都不多写记录**；记录操作者固定为调度系统标识并保留原观察人，**不丢操作者**。`POST /api/aqua/disease/followup-tasks/scan` 可人工触发同一逻辑核查。
- 全程留证：档案变更/观察单/调度/调度失败/解除/复查任务均写 `t_disease_change_evidence`（含操作前后快照、幂等请求号与操作者），可经 `GET /api/aqua/disease/evidence` 追溯。

验收：`mvn test -Dtest=DiseaseIsolationAcceptanceTest`，15 个检查点逐段输出报告，失败即停并给出停点。

## 潮汐窗口排班（aqua-5）

模块包路径 `com.evops.aqua.tide`，在台账之上打通：潮汐表/禁作天气/人员班次/设备登记 → 按海区潮汐生成换水候选 → 叠加天气与班次形成可执行窗口 → 排班绑窗口池塘设备 → 发布后审批改期 → 自动顺延 → 临时关闭通知班组留回执，全程留证。

- 潮汐表（`POST /api/aqua/tide/events`）：按海区逐条登记高潮/低潮事件；requestId 幂等，同海区同时刻同潮型自然键幂等，重复登记返回首次记录。
- 禁作天气（`/api/aqua/tide/weather-bans`）与人员班次（`/api/aqua/tide/shifts`）：按海区登记禁作时段、按班组登记值勤时段，均 requestId 幂等。
- 窗口生成（`POST /api/aqua/tide/windows/generate`）：取海区当日高潮事件向前后各推 margin 分钟得候选时段，**剔除与禁作天气重叠的候选、要求班组班次全覆盖**，被排除候选逐条说明原因；同一潮汐事件只生成一个窗口，重复生成不多写。
- **统一口径（临界时间归属）**：所有时段一律按半开区间 `[start, end)` 判定重叠——端点相接的天气/排班不算冲突；班次覆盖按闭区间——端点相等算覆盖；**窗口归属日 = 窗口开始时间所在自然日**（跨午夜窗口归开始日），窗口查询、排班、通知全部按此口径归属。
- 排班（`/api/aqua/tide/schedules`）：窗口+池塘+设备，校验窗口开放、池塘海区一致、设备可用；**同一设备同一时间只能服务一口池**（半开区间判定，端点相接允许），同一池塘同一窗口只排一班。
- 发布与改期：`POST /{id}/publish` 发布；`POST /{id}/reschedule` 改期——**已发布排班必须登记调度审批人与审批意见**，否则拒绝；待发布排班直接改。
- 自动顺延（`POST /{id}/postpone`）：系统自动挑选同海区下一个设备空闲的开放窗口生成新排班（origin 链接、继承发布态）；**原计划行的时间/池塘/设备一律不变**，仅状态置 POSTPONED；requestId 幂等。
- 临时关闭（`POST /api/aqua/tide/windows/{id}/close`）：登记关闭人与原因，向窗口内受影响班组逐条发通知；班组签收（`POST /api/aqua/tide/notifications/{id}/ack`）**留回执**（签收人/时间/回执备注）；重复关闭不多发、重复签收不覆盖首次回执。
- 全程留证：潮汐/天气/班次/设备登记、成窗、排班创建/发布/改期/顺延、关窗、通知、签收均写 `t_tide_change_evidence`（含操作前后快照与幂等请求号），可经 `GET /api/aqua/tide/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=TideWindowScheduleAcceptanceTest`，11 个检查点逐段输出报告，失败即停并给出停点。




## 养殖用药管理（aqua-6）

模块包路径 `com.evops.aqua.med`，在台账与疫病模块之上打通：药品档案/批号入库 → 领用单关联池塘、病情（疫病观察单）与药品批号 → 按剂量×水体估算用量 → 提交后计算休药期并锁定预计出池日 → 审核通过同步扣减库存 → 兽医/仓管分角色视图，全程留证。

- 药品档案（`POST /api/aqua/med/drugs`）：登记标准剂量（每 m³ 水体用量）与休药期天数；requestId 幂等，重复登记返回首条。
- 批号入库（`POST /api/aqua/med/batches`）：一批一行两量账（可用量 = 入库 − 已消耗）；**批号不可删除**，用尽置 EXHAUSTED；requestId 幂等。
- 领用单（`POST /api/aqua/med/requisitions`）：关联池塘、病情观察单（须同池）与药品批号；**按剂量 × 水体（面积 × 水深）自动估算使用量**，剂量可按行覆盖档案默认值，剂量与休药期建单时冻结快照；requestId 幂等。
- 提交（`POST /{id}/submit`）：草稿转待审核，逐明细计算休药结束日（用药日 + 休药期天数），并锁定池塘预计出池日——**不同药品叠加时取最晚结束日**；休药期未结束台账出池一律拒绝，并提示最早可出池日。
- 审核（`POST /{id}/approve`）：**审批人与审批意见缺一不可，缺任一领用单停留待处理（PENDING）**；**库存扣减与领用审核同一事务**——任一明细批号可用量不足则整体回滚、单停留待审核、失败原因独立事务留证；重复审批幂等不多扣。
- 驳回与撤回：`POST /{id}/reject` 驳回须登记审批人与意见；`POST /{id}/withdraw` **只能撤销未审核单**（草稿/待审核），已审核单一律拒绝；驳回/撤回后释放该单对池塘的休药锁定。
- 角色视图（`GET /api/aqua/med/requisitions/{id}?role=`）：**兽医（VET）看处方明细**（病情、剂量、休药期），**仓管（KEEPER）只看库存字段**（批号、数量、效期），看不到处方信息。
- 全程留证：药品登记、批号入库、领用创建/提交/审核/驳回/撤回、库存扣减、休药锁定/释放均写 `t_med_change_evidence`（含操作者、前后快照与幂等请求号），可经 `GET /api/aqua/med/evidence` 按对象或 requestId 追溯；池塘休药锁定经 `GET /api/aqua/med/withdrawal-locks?pondId=` 查询。

验收：`mvn test -Dtest=MedRequisitionAcceptanceTest`，15 个检查点逐段输出报告，失败即停并给出停点。

## 捕捞预约与规格分仓（aqua-8）

模块包路径 `com.evops.aqua.fishing`，打通：分级规则版本 → 按规格建库位 → 客户订单拆分预约 → 锁定占用库位 → 现场按实际规格分仓 → 检验复位 → 临时换车通知客户 → 完成生成差异单 → 订单完成冻结规则快照，全程留证。

- 分级规则版本（`POST /api/aqua/fishing/grading-rules`）：版本下一组「规格 + 重量区间」（**半开区间 [min, max)**，行间不重叠）；**新版本发布后旧版本自动停用**，现场判级永远取当时生效版本；requestId 幂等。
- 库位（`/api/aqua/fishing/bins`）：一仓对应一个规格；`POST /{id}/inspect` 检验完成——**库位状态与待检标识同一事务同步复位**（装货即 LOADING/FULL + 待检，检验后 CLEARED + 清除待检，不留中间态）。
- 客户订单（`POST /api/aqua/fishing/orders`）：按**规格、重量区间和交付时间**拆分为捕捞预约，一行一条；重量区间须落在生效规则对应该规格区间内；requestId 幂等——**补传/重试返回首次订单，不会拆出第二批预约**。
- 预约锁定（`POST /api/aqua/fishing/reservations/{id}/lock`）：指定或自动挑选本规格空闲库位占用；**锁定后库位不能被其他订单占用**（指定他单库位直接拒绝）。
- 现场分仓（`POST /{id}/loads`，requestId 强制幂等）：实际均重按当时生效规则判实际规格，入对应规格库位（同订单可共用，他单占用拒绝；超容量拦截）；**补录/重试只形成一条可用记录，不重复加账**。
- 完成（`POST /{id}/complete`）：**短装/超装自动生成差异单**（每预约至多一张，数量一致不生成）；完成前库位须先检验；完成交付后库位腾空释放、可被后续订单使用；全部预约完成后订单完成并**冻结当时分级规则快照**——已完成订单保留当时分级规则，新版本不影响。
- 临时换车（`POST /{id}/vehicle-change`）：**原因必填**，换车记录与客户通知同一事务生成；客户签收（`POST /api/aqua/fishing/notifications/{id}/ack`）**留回执**，重复签收不覆盖首次回执；重试不重复换车、不重复通知。
- 全程留证：规则发布/停用、库位登记/装货/检验/释放、订单创建/完成、预约拆分/锁定/完成、分仓、差异单、换车、通知签收均写 `t_fishing_change_evidence`（含操作者、前后快照与幂等请求号），可经 `GET /api/aqua/fishing/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=FishingReservationAcceptanceTest`，13 个检查点逐段输出报告，失败即停并给出停点。

## 池塘水质传感器接入（aqua-9）

模块包路径 `com.evops.aqua.sensor`，打通：设备登记（含签名密钥）→ 离线批量上传盐度/溶氧/温度 → 验签后按设备时间与序号归并 → 异常读数待复核 → 日报按池按日按指标汇总 → 审核锁定区间，全程留证。

- 设备档案（`POST /api/aqua/sensor/devices`）：按设备编码登记，绑定池塘与签名密钥；requestId 幂等，重复登记返回首条。
- 批量上传（`POST /api/aqua/sensor/uploads`）：请求头 `X-Device-Code` / `X-Request-Id` / `X-Signature`，报文原文整体接收；**签名 = HMAC-SHA256(设备密钥, requestId + "\n" + 原始报文) 的十六进制串**。**签名失败或设备未登记的包整包隔离并保留原文**（`GET /api/aqua/sensor/quarantine` 可查原文与原因），绝不进入归并；退役设备上传直接拒绝。
- 归并（按设备时间与序号）：读数以「设备 + 指标 + 设备时间 + 序号」为自然键去重——**同 requestId 整包重传返回首次结果；新 requestId 部分重叠只补缺失槽位**；归并时冻结当时规则版本与判定结论。
- 异常读数待复核（`GET /api/aqua/sensor/review-queue`）：超出规则正常范围的读数入队且**暂不计入日报**；`POST /review-queue/{id}/resolve` 复核——CONFIRMED 确认有效补入未审核日报，EXCLUDED 剔除永不计入；复核人与意见必填，同结论重复复核幂等。
- 日报（`GET /api/aqua/sensor/daily-reports`）：按（池, 日, 指标）一行，统计（样本数/均值/最小/最大）**由归并读数重算得出，重新上传不会重复计入**；`POST /daily-reports/{id}/review` 审核后区间锁定（重复审核幂等）——**迟到数据只能补充未审核区间**，落入已审核区间的读数记 LATE_REJECTED 留痕且响应逐条说明，不入日报。
- 设备更换（`POST /api/aqua/sensor/devices/{id}/replace`）：原因与操作人必填，同一事务登记新设备、旧设备置 RETIRED、写新旧关联行；`GET /devices/{id}/lineage` 沿关联链双向追溯谱系；日报按池连续，换设备不断档。
- 规则版本（`POST /api/aqua/sensor/rule-versions`）：盐度/溶氧/温度三组正常范围；**规则调整只能发布新版本（旧版本自动停用），已归并读数与日报冻结旧版本结论，绝不回改**。
- 全程留证：设备登记/更换、规则版本发布与停用、上传归并、迟到拒绝、复核、日报审核、隔离均写 `t_sensor_change_evidence`（含操作前后快照与幂等请求号），可经 `GET /api/aqua/sensor/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=SensorIngestAcceptanceTest`，15 个检查点逐段输出报告，失败即停并给出停点。

## 养殖成本分摊与批次结转（aqua-10）

模块包路径 `com.evops.aqua.cost`，打通：财务凭证 → 饲料/人工/药品/能源按池按批归集 → 转池按数量与生物量权重分配未结成本 → 出池结转不可变快照 → 差异待审核 → 角色分视图与同口径报表，全程留证。

- 财务凭证（`POST /api/aqua/cost/vouchers`，requestId 幂等）：**人工补录必须先登记并引用有效凭证号**——无凭证号、凭证不存在或已作废的补录一律拒绝，且不落任何费用行。
- 费用归集（`POST /api/aqua/cost/entries`，requestId 幂等）：FEED/LABOR/MED/ENERGY 四类按「池+批」归集，凭条只追加；批次不在该池或**已出池结转一律拒绝**——已结转批次不能被新单据悄悄改写。
- 转池分配：台账换池 `POST /api/aqua/batches/{id}/transfer` 扩展支持分池（传小于在养数量的 `quantity` 与转出 `biomassKg`，目标池自动派生接收批次，源批核减留存）。成本模块同事务监听：**整批转移未结成本 100% 随批走不重算金额**；**分池按 50% 数量权重 + 50% 生物量权重分配**（生物量全为 0 时退化为纯数量权重），权重与分配额冻结在 `t_cost_transfer_line`，接收批补 CARRYOVER 凭条可逐笔追溯。生物量经 `POST /api/aqua/batches/{id}/biomass` 核定；疫病隔离调度/解除同样触发成本账随批移动。
- 出池结转（`POST /api/aqua/batches/{id}/harvest` 自动触发）：在养未结成本全部结转，**形成不可变快照**（头 + 逐费用类型行 + JSON 快照），余额清零；之后费用凭条与差异单均无法改写该批。
- 差异调整（`POST /api/aqua/cost/adjustments`，requestId 幂等）：账面修正只能走差异单，建单即 **PENDING 待审核**；`POST /{id}/approve` 审核人与意见缺一不可（缺则停留待审核），通过后才入余额并补凭条，驳回不动账；重复审核幂等；核减超余额、已结转批次均拦截。
- 角色视图（`GET /api/aqua/cost/allocation-report?role=FINANCE|PRODUCTION`、`/batches/{id}`、`/export`）：**财务（FINANCE）看金额与凭证；生产（PRODUCTION）只看数量/生物量/单据数等汇总，任何金额与凭证号字段不下发**，凭条、差异单、凭证列表同样按角色脱敏。
- **池塘置为无效同口径**（`POST /api/aqua/ponds/{id}/disable`，原因必填）：页面（JSON）、CSV 导出与后台聚合共用同一 `loadRows` 查询，默认**只统计 ACTIVE 池塘**，停用后三个出口同时不再出现该池数据并留 DISABLE 证据；历史费用凭条、结转快照一律保留不删；显式 `includeDisabled=true` 三口径仍一致地放开。
- 全程留证：凭证/归集/转池分配/出池结转/差异审核/池塘停用均写 `t_cost_change_evidence`，可经 `GET /api/aqua/cost/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=CostAllocationCarryoverAcceptanceTest`，12 个检查点逐段输出报告，失败即停并给出停点。

## 海区禁养规则（aqua-11）

模块包路径 `com.evops.aqua.noculture`，在台账之上打通：监管人员维护禁养范围/生效时间/依据文件 → 投苗、转池、捕捞三个入口空间与时间校验 → 规则新增即冻结在途批次（已完成记录只提示不回写）→ 空间重叠按更严格规则执行 → 撤销逐冻结账补偿解冻（仍被他规冻结的挂起），冻结/解冻/补偿全程留证且账账对应。

- 禁养规则维护（`POST /api/aqua/noculture/rules`，requestId 强制幂等）：范围支持整海区 `AREA`（海区相等即命中）与经纬度多边形 `POLYGON`（池塘经纬度做点-多边形判定，至少 3 个顶点）；登记生效区间（半开 `[effectiveFrom, effectiveTo)`，`effectiveTo` 空表示长期有效）、依据文件文号/名称与严格级别；**规则只能新增与撤销，无删除接口**。重复 requestId 返回首条规则。
- 规则新增即冻结在途批次：扫描全部批次，**空间命中的在途（STOCKED）批次逐批冻结**（`t_noculture_freeze`），投苗/转池/捕捞三入口一律拦截；**空间命中但已完成（HARVESTED）的批次只写提示（COMPLETED_NOTICE），绝不回写台账、不改批次状态**。
- 三入口空间 + 时间校验（`NoCultureGuard`）：投苗校验目标池、转池同时校验批次冻结态与目标池、捕捞校验批次冻结态与所在池；**必须空间命中且操作日期落入规则生效半开区间才拦截**——未来生效不提前拦、到期自动放行。
- **空间边界重叠按更严格规则执行**：同一点可被多条规则命中并分别冻结，三入口按 `strictLevel` 最大者裁决，提示共命中规则数与最严规则依据文件。
- 撤销与补偿（`POST /api/aqua/noculture/rules/{id}/revoke`，requestId 幂等）：规则置 REVOKED 并逐冻结账补偿——**批次仍被其他生效重叠规则冻结的补偿记 `SKIPPED_STILL_BOUND`（保持冻结），无其他约束的记 `RELEASED` 解冻放行**；重复撤销回放既有补偿、不多写。
- **部分成功账账对应**：空间匹配记录（`t_noculture_match`）与冻结账双向外键对应；每条冻结至多一条撤销补偿（`t_noculture_compensation`，`uk freeze_id`），单批冻结失败降级为 FAILED 匹配并独立事务留证，不影响其他批次；`GET /api/aqua/noculture/rules/{id}/reconcile` 勾稽核对「禁养区域→空间匹配→冻结账→补偿」链路，出现悬挂即 `balanced=false`。
- 全程留证：规则登记/撤销、每次冻结、解冻、挂起未解冻、已完成提示、冻结失败均写 `t_noculture_change_evidence`（含操作者、前后快照、幂等请求号），可经 `GET /api/aqua/noculture/evidence` 按对象或 requestId 追溯。
- 池塘档案补充中心点经纬度（`POST /api/aqua/ponds` 的 `longitude`/`latitude`，可空）；无坐标池塘只能被整海区规则命中，不会被多边形规则误伤。

验收：`mvn test -Dtest=NoCultureAcceptanceTest`，11 个检查点逐段输出报告，失败即停并给出停点。

## 养殖设备保养工单（aqua-12）

模块包路径 `com.evops.aqua.maint`，打通：增氧机/泵站/投饵机建档（一物一序列号备件、停机窗口、维修人员资质）→ 运行小时与日历双轨保养计划 → 到期扫描生成工单（关联停机窗口/备件/人员，冻结计划版本）→ 派工 → 开工停机 → 换件留旧件序列号 → 完工 → 验收 → 逐项安全确认 → 关闭恢复运行；逾期自动升级，同一操作并发裁决谁生效谁被拒并保留审计。

- 设备档案（`POST /api/aqua/maint/devices`）：AERATOR 增氧机 / PUMP_STATION 泵站 / FEEDER 投饵机；状态 RUNNING/DOWN。运行小时上报（`POST /{id}/meter-readings`，requestId 幂等）只追加，**累计读数不得回退**；`POST /{id}/installed-parts` 登记初始装机序列号基线。
- 停机窗口（`POST /api/aqua/maint/devices/{deviceId}/windows`）：半开区间 `[start,end)`，同设备窗口半开重叠拒绝、端点相接允许，requestId 幂等；**工单计划时段必须被设备的开放窗口完整覆盖，一张窗口只安排一张工单**。
- 备件（`/api/aqua/maint/spare-catalogs`、`/spare-parts`）：品类目录 + 一物一序列号实例（IN_STOCK/RESERVED/INSTALLED/SCRAPPED）。**工单建立即按需求行锁定在库实例，库存不足整单回滚不残留半占用**；完工未用件释放回在库。
- 维修人员（`POST /api/aqua/maint/workers`）：按设备类型登记资质；派工时**在职且资质覆盖设备类型，冲突一次性列全**；工单头记主修、明细支持多人，留派工人。
- 保养计划（`POST /api/aqua/maint/plans`）：一设备一行，**运行小时（间隔小时）与日历周期（间隔天+起算日）双轨**，可单开或同开，计划挂备件模板；`POST /{id}/adjust` 调整间隔/开关/起算日，version_no 递增——**调整只影响尚未生成的工单，已生成工单冻结旧版本（plan_version_no、到期读数/到期日、备件快照）永不回改，且不追溯补单**。
- 工单生成（Quartz 每 30 分钟扫描，`POST /api/aqua/maint/orders/scan` 可手工触发同逻辑）：小时轨「当前读数−上次生成基准 ≥ 间隔」到期、日历轨「基准日+间隔天 ≤ 今天」到期；匹配设备最早可用开放窗口后以独立事务生成，**uk(plan,trigger_source,due_token) 与同轨未结单判定保证重跑幂等不多写**；到期但无可用窗口跳过并留证（重扫不重复刷证据）。
- 工单生命周期（`/api/aqua/maint/orders/{id}`）：PLANNED → ASSIGNED（派工关联人员）→ IN_PROGRESS（**开工即设备 DOWN**，按设备类型生成 6 项安全清单）→ COMPLETED（释放未用备件）→ ACCEPTED（**验收人与意见缺一不可**）→ CLOSED。**未验收（非 ACCEPTED）不能关闭**。
- 备件替换（`POST /{id}/parts/{catalogId}/replace`，requestId 幂等）：新件必须是本单锁定实例、旧件序列号必须是当前装在该设备上的在册实例；**新件转 INSTALLED、旧件转 SCRAPPED 永久保留（原装机信息、拆除工单均不清除），换件记录同时留存新旧序列号与换件人**。
- 安全确认（`POST /{id}/safety-checks/{checkId}/confirm`）：通用 3 项 + 设备专项 3 项逐项确认，重复确认不覆盖首次确认人；**设备恢复运行（关闭工单）前全部检查项必须 CONFIRMED，未确认项在拒绝信息中逐项列出**。
- 逾期自动升级（Quartz 每 15 分钟，`POST /orders/escalate-scan` 手工触发）：过完工期限未完工 NORMAL → WARNING，逾期超 24 小时 → ESCALATED；**升级只追加不回落，重扫幂等不多写**，操作者固定 system-scheduler。
- **同一操作并发裁决**（开工 `WORK_ORDER_START:{工单}`、恢复运行 `DEVICE_RESUME:{设备}`、临时开票 `MANUAL_ORDER_WINDOW:{窗口}`）：库级唯一锁串行化，**先到请求 EFFECTIVE 生效，其余 CONCURRENT_REJECTED 立即被拒，拒绝响应与审计均写明生效请求号、双方操作者与拒绝原因**；被拒审计独立事务落库不随业务回滚，锁在业务事务完成后释放；同 requestId 重放幂等返回首次结果。`GET /api/aqua/maint/concurrent-audits` 可按业务键/请求号双向追溯。
- 全程留证：设备登记/初始装机/运行小时/窗口/计划创建调整/工单生成跳过/派工/开工/换件/完工/验收/安全确认/关闭/逾期升级均写 `t_maint_change_evidence`（含操作者、前后 JSON 快照与幂等请求号），可经 `GET /api/aqua/maint/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=MaintenanceWorkOrderAcceptanceTest`，18 个检查点逐段输出报告（含真实双线程并发竞争校验），失败即停并给出停点。

## 批次出池检验与放行签名（aqua-13）

模块包路径 `com.evops.aqua.release`，在台账之上打通：按品种+用途组合发布检验阈值版本 → 一批一检验单（冻结版本与样品）→ 实验室按样品逐项目录入 → 系统比对阈值生成放行建议 → 检测报告与样品编号一一对应 → 退回补检保留原结论/原因/操作时间 → 复检追加不覆盖初检 → 授权人员电子签名后批次才进入发货，全程留证。

- 阈值版本（`POST /api/aqua/release/rule-versions`，requestId 幂等）：检验项目按**品种 + 用途组合**逐指标配置闭区间 [min,max]（端点可空为单边约束）；**新版本发布后旧版本自动停用**，检验单建立即冻结版本快照，历史单复检仍按冻结版本判定。
- 检验单（`POST /api/aqua/release/inspections`，requestId 幂等）：一批一单（同批存在未完结单拒绝），批次须在养，随单登记样品；未配置该品种+用途组合的生效版本拒绝建单。
- 实验室录入（`POST /inspections/{id}/lab-submit`，requestId 幂等）：PENDING 落初检轮、RETURNED 落复检轮；样品×必检项目必须录全，**缺项一次性列全拒绝**；系统逐项按冻结阈值比对 PASS/FAIL，任一不合格整单建议 **FAIL**，全合格建议 **PASS**。
- **复检不覆盖初检**：复检只追加新轮次（`origin_round_id` 指向被退回轮次），初检结果值、判定、提交人/时间原样保留；同 requestId 重放不产生新轮次。
- **退回补检（`POST /inspections/{id}/return`，原因必填）**：初检结论、退回原因、操作人与操作时间逐行写入结论历史（多次退回多次留痕互不覆盖），检验单头保留最近一次退回事实。
- **检测报告（`POST /inspections/{id}/reports`，requestId 幂等）**：报告号全局唯一且与样品编号**一一对应**——一个样品只能出一份报告，换号重出/占用他样报告号均拒绝；`GET /api/aqua/release/reports/{reportNo}` 按报告号唯一定位样品与逐项结果。
- **授权电子签名（`POST /inspections/{id}/release`，requestId 幂等）**：签名人须先登记（`POST /signers`）且启用、口令正确；未登记/错口令/已禁用一律拒绝；系统对检验单关键事实生成 HMAC-SHA256 电子签章；**系统建议 FAIL 不得签名放行**。签名放行后批次才允许出池——守卫接入台账 `POST /api/aqua/batches/{id}/harvest`，未放行直接拦截；从未建检验单的历史批次行为不变。
- 旧数据回放（`GET /api/aqua/release/batches/{batchId}/legacy-replay`）：回放海水养殖管理旧版自由文本抽检 `t_inspection_record`（旧记录原样保留不回改），从检验模型/阈值判定/复检/退回/样品报告/签名放行/留证 7 个维度列示新旧差异并逐条解释，`allDifferencesExplained=true` 方可提交新版本。
- 全程留证：版本发布、建单、初检/复检录入、出报告、退回、签名、签名人启停均写 `t_pi_change_evidence`（含操作者、前后 JSON 快照与幂等请求号），可经 `GET /api/aqua/release/evidence` 按对象或 requestId 追溯。

验收：`mvn test -Dtest=ReleaseInspectionAcceptanceTest`，14 个检查点（CP0–CP13）逐段输出报告，失败即停并给出停点。
