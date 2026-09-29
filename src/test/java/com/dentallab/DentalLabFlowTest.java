package com.dentallab;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端流程测试（真实 PostgreSQL，dentallab_test 库）：
 * 下单→接单→八道工序→发货→签收→试戴→返工→责任判定→重做→再签收→结算→月结复核；
 * 加急+延误场景；色号争议处理（重新制作 / 不重新制作两条分支）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DentalLabFlowTest {

    @org.junit.jupiter.api.BeforeEach
    void configureRestTemplate() {
        // JDK HttpClient：避免 HttpURLConnection 对 401 的流式重试问题
        plain = new RestTemplate(new JdkClientHttpRequestFactory());
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private RestTemplate plain;

    private static String doctorToken;
    private static String receiverToken;
    private static String tech1Token;
    private static String tech2Token;
    private static String qcToken;
    private static String qcMgrToken;
    private static String clinicAdminToken;
    private static String labAdminToken;
    private static String serviceToken;
    private static String financeToken;

    private static Long orderId;
    private static Long item1;
    private static Long item2;
    private static Long reworkId;
    private static Long zirconiaBatchId;
    private static Long statementId;

    // ---------- 主流程 ----------

    @Test
    @Order(1)
    void loginAllRoles() {
        doctorToken = login("doctor1", "doctor123");
        receiverToken = login("receiver", "lab123");
        tech1Token = login("tech1", "tech123");
        tech2Token = login("tech2", "tech123");
        qcToken = login("qc1", "qc123");
        qcMgrToken = login("qcmgr1", "qcmgr123");
        clinicAdminToken = login("clinicadmin1", "admin123");
        labAdminToken = login("labadmin", "lab123");
        serviceToken = login("service1", "service123");
        financeToken = login("finance1", "finance123");

        // 错误密码应 401
        ResponseEntity<Map> bad = plain.postForEntity(url("/api/auth/login"),
                Map.of("username", "doctor1", "password", "wrong"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());
        // 无令牌访问受保护接口应 401
        ResponseEntity<Map> noAuth = plain.getForEntity(url("/api/orders"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode());
    }

    @Test
    @Order(2)
    void clinicCreatesOrder() {
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0001");
        body.put("prosthesisType", "ALL_CERAMIC_CROWN");
        body.put("impressionMethod", "DIGITAL_SCAN");
        body.put("toothPositions", List.of("11", "12"));
        body.put("biteRelation", "正常覆合覆盖");
        body.put("shade", "VITA A2");
        body.put("doctorNotes", "邻面接触稍紧");
        body.put("expectedTryInDate", LocalDate.now().plusDays(7).toString());
        body.put("materialRequirement", "氧化锆全瓷");
        body.put("difficulty", 3);
        Map<String, Object> resp = data(post(doctorToken, "/api/orders", body));
        orderId = toLong(resp.get("id"));
        assertNotNull(resp.get("orderNo"));
        assertEquals("SUBMITTED", resp.get("status"));
        List<Map<String, Object>> items = list(resp, "toothItems");
        assertEquals(2, items.size());
        item1 = toLong(items.get(0).get("id"));
        item2 = toLong(items.get(1).get("id"));

        // 数字口扫文件登记
        Map<String, Object> scan = data(post(doctorToken, "/api/orders/" + orderId + "/scan-files",
                Map.of("fileName", "pat0001_upper.stl", "format", "STL",
                        "sizeBytes", 2048576, "storagePath", "oss://scans/pat0001_upper.stl")));
        assertEquals("STL", scan.get("format"));

        // 诊所医生不能给别的角色下单权限之外的操作：技工角色不能下单
        ResponseEntity<Map> forbidden = postRaw(tech1Token, "/api/orders", body);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    @Order(3)
    void labAcceptsAndProduces() {
        // 未接单前不能登记工序
        ResponseEntity<Map> early = postRaw(tech1Token, "/api/orders/" + orderId + "/items/" + item1 + "/stages",
                Map.of("stage", "MODEL_RECEIVING"));
        assertEquals(HttpStatus.BAD_REQUEST, early.getStatusCode());

        data(post(receiverToken, "/api/orders/" + orderId + "/accept", null));

        for (Long item : List.of(item1, item2)) {
            // 模型接收（前台）
            data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("MODEL_RECEIVING", "收到口扫数据与模型", null, null, null)));
            // 扫描设计（技工1），写设计摘要
            Map<String, Object> design = stageBody("SCAN_DESIGN", "设计完成", null, null, null);
            design.put("designSummary", "全瓷冠设计，边缘位于龈上0.5mm");
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages", design));
        }

        // 医生对 11 牙位在线批注（设计未冻结，不触发异常）
        data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item1 + "/annotations",
                Map.of("content", "切端透明度再低一点")));

        for (Long item : List.of(item1, item2)) {
            // 医生确认
            data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item + "/confirm",
                    Map.of("note", "设计确认，可以制作")));
            // 排牙（技工1）
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("TEETH_ARRANGEMENT", "排牙完成", null, null, null)));
        }

        // 材料批次：查氧化锆瓷块批次
        Map<String, Object> materials = data(get(labAdminToken, "/api/inventory/materials"));
        List<Map<String, Object>> mats = list(materials, "$data");
        Map<String, Object> zirconia = mats.stream()
                .filter(m -> "ZIRC".equals(m.get("code"))).findFirst().orElseThrow();
        List<Map<String, Object>> batches = list(zirconia, "batches");
        zirconiaBatchId = toLong(batches.get(0).get("id"));
        BigDecimal before = new BigDecimal(String.valueOf(batches.get(0).get("remainingQty")));

        for (Long item : List.of(item1, item2)) {
            // 烧结（技工2，使用氧化锆瓷块 1 块）
            data(post(tech2Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("SINTERING", "烧结完成", zirconiaBatchId, "1", List.of("photo_sinter_" + item + ".jpg"))));
            // 抛光（技工1）
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("POLISHING", "抛光完成", null, null, null)));
            // 质检（质检员）
            data(post(qcToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("QC", "质检合格", null, null, List.of("photo_qc_" + item + ".jpg"))));
            // 配送工序登记（前台）
            data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("DELIVERY", "已打包待发货", null, null, null)));
        }

        // 库存被扣减 2 块
        Map<String, Object> after = data(get(labAdminToken, "/api/inventory/materials"));
        Map<String, Object> zircAfter = list(after, "$data").stream()
                .filter(m -> "ZIRC".equals(m.get("code"))).findFirst().orElseThrow();
        assertEquals(0, before.subtract(new BigDecimal("2"))
                .compareTo(new BigDecimal(String.valueOf(zircAfter.get("remainingQty")))));

        // 工序顺序错误应失败：已完成全部工序的牙位不能再登记
        ResponseEntity<Map> wrongSeq = postRaw(tech1Token, "/api/orders/" + orderId + "/items/" + item1 + "/stages",
                stageBody("POLISHING", "重复抛光", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, wrongSeq.getStatusCode());

        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("QC_PASSED", detail.get("status"));
    }

    @Test
    @Order(4)
    void shipAndSignThenTryIn() {
        // 发货
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + orderId + "/shipments", null));
        Long shipmentId = toLong(shipment.get("id"));
        assertNotNull(shipment.get("trackingNo"));

        // 诊所完好签收 → 自动生成试戴安排
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("TRY_IN", detail.get("status"));
        assertFalse(list(detail, "tryIns").isEmpty());

        // 牙位 11 试戴密合
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item1);
        fit.put("result", "FIT");
        fit.put("feedback", "密合良好，患者满意");
        fit.put("experienceScore", 5);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", fit));

        // 牙位 12 试戴不密合 → 自动异常 + 自动返工
        Map<String, Object> misfit = new HashMap<>();
        misfit.put("toothItemId", item2);
        misfit.put("result", "MISFIT");
        misfit.put("feedback", "边缘不密合，食物嵌塞");
        misfit.put("experienceScore", 2);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", misfit));

        Map<String, Object> after = data(get(doctorToken, "/api/orders/" + orderId));
        List<Map<String, Object>> exceptions = list(after, "exceptions");
        assertTrue(exceptions.stream().anyMatch(e -> "TRY_IN_MISFIT".equals(e.get("type"))));
        List<Map<String, Object>> reworks = list(after, "reworks");
        assertEquals(1, reworks.size());
        assertEquals("PENDING", reworks.get(0).get("responsibility"));
        reworkId = toLong(reworks.get(0).get("id"));
    }

    @Test
    @Order(5)
    void reworkResponsibilityAndRedo() {
        // 客服上报“返工责任不清”异常
        data(post(serviceToken, "/api/orders/" + orderId + "/exceptions",
                Map.of("type", "REWORK_DISPUTE", "description", "诊所与技工所对返工责任有分歧",
                        "toothItemId", item2)));

        // 牙位 12 重做：从模型接收重新开始
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("MODEL_RECEIVING", "返工模型接收", null, null, null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SCAN_DESIGN", "返工设计", null, null, null)));
        data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item2 + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("TEETH_ARRANGEMENT", "返工排牙", null, null, null)));
        data(post(tech2Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SINTERING", "返工烧结", zirconiaBatchId, "1", null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("POLISHING", "返工抛光", null, null, null)));
        data(post(qcToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("QC", "返工质检合格", null, null, null)));
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("DELIVERY", "返工打包", null, null, null)));

        // 返工单应已自动完成
        Map<String, Object> reworks = data(get(doctorToken, "/api/orders/" + orderId + "/reworks"));
        Map<String, Object> rework = list(reworks, "$data").get(0);
        assertEquals("COMPLETED", rework.get("status"));

        // 只重发牙位 12
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + orderId + "/shipments",
                Map.of("itemIds", List.of(item2))));
        Long shipmentId = toLong(shipment.get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));

        // 牙位 12 再次试戴密合 → 订单完成
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item2);
        fit.put("result", "FIT");
        fit.put("feedback", "返工后密合");
        fit.put("experienceScore", 4);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", fit));

        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("COMPLETED", detail.get("status"));
        assertNotNull(detail.get("completedAt"));

        // 返工责任未判定前不能结算
        ResponseEntity<Map> blocked = postRaw(financeToken, "/api/orders/" + orderId + "/settlement", null);
        assertEquals(HttpStatus.BAD_REQUEST, blocked.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(blocked.getBody()).get("error")).contains("返工责任"));

        // 技工所管理员判定责任：技工所承担 200
        data(post(labAdminToken, "/api/reworks/" + reworkId + "/assign-responsibility",
                Map.of("responsibility", "LAB", "reworkCost", 200)));
    }

    @Test
    @Order(6)
    void settlementReflectsReworkResponsibility() {
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + orderId + "/settlement", null));
        // 基础费 500×2=1000，难度3系数1.30 → 1300；技工所承担返工 200 扣减 → 1100
        assertMoney("1000.00", s.get("baseAmount"));
        assertMoney("1.30", s.get("difficultyMultiplier"));
        assertMoney("0.00", s.get("rushFee"));
        assertMoney("0.00", s.get("reworkClinicShare"));
        assertMoney("200.00", s.get("reworkLabDeduction"));
        assertMoney("0.00", s.get("deliveryPenalty"));
        assertMoney("1100.00", s.get("totalAmount"));
    }

    @Test
    @Order(7)
    void monthlyStatementRequiresReworkReview() {
        String ym = java.time.YearMonth.now().toString();
        // 诊所未复核返工 → 月结单生成但确认被拒
        Map<String, Object> statement = data(post(financeToken, "/api/statements/generate",
                Map.of("clinicId", 1, "yearMonth", ym)));
        statementId = toLong(statement.get("id"));
        assertEquals(1, ((Number) statement.get("unreviewedReworkCount")).intValue());
        assertMoney("1100.00", statement.get("totalAmount"));

        ResponseEntity<Map> confirmBlocked = postRaw(clinicAdminToken, "/api/statements/" + statementId + "/confirm", null);
        assertEquals(HttpStatus.BAD_REQUEST, confirmBlocked.getStatusCode());

        // 诊所复核全部返工
        Map<String, Object> unreviewed = data(get(clinicAdminToken, "/api/reworks/unreviewed"));
        assertEquals(1, list(unreviewed, "$data").size());
        data(post(clinicAdminToken, "/api/reworks/" + reworkId + "/clinic-review", null));

        // 复核后确认成功
        Map<String, Object> confirmed = data(post(clinicAdminToken, "/api/statements/" + statementId + "/confirm", null));
        assertEquals("CONFIRMED", confirmed.get("status"));
    }

    @Test
    @Order(8)
    void toothBoardAndPerformance() {
        // 按牙位看板：每颗牙的设计、工序、返工责任
        Map<String, Object> board = data(get(labAdminToken, "/api/orders/" + orderId + "/tooth-board"));
        List<Map<String, Object>> teeth = list(board, "teeth");
        assertEquals(2, teeth.size());
        Map<String, Object> tooth12 = teeth.stream()
                .filter(t -> "12".equals(t.get("toothPosition"))).findFirst().orElseThrow();
        assertFalse(list(tooth12, "stages").isEmpty());
        assertEquals(1, list(tooth12, "reworks").size());
        assertEquals("LAB", list(tooth12, "reworks").get(0).get("responsibility"));

        // 技工绩效
        Map<String, Object> perf = data(get(labAdminToken, "/api/performance/technicians"));
        List<Map<String, Object>> techs = list(perf, "$data");
        assertEquals(2, techs.size());
        Map<String, Object> tech1 = techs.stream()
                .filter(t -> "张技工".equals(t.get("technicianName"))).findFirst().orElseThrow();
        assertTrue(((Number) tech1.get("stagesCompleted")).longValue() >= 5);
    }

    // ---------- 第二场景：加急 + 配送延误 + 异常处理 ----------

    @Test
    @Order(9)
    void rushOrderWithLateDelivery() {
        // 诊所下单（烤瓷冠，单牙位，难度1）
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0002");
        body.put("prosthesisType", "PFM_CROWN");
        body.put("impressionMethod", "TRADITIONAL");
        body.put("toothPositions", List.of("36"));
        body.put("shade", "VITA B1");
        body.put("expectedTryInDate", LocalDate.now().plusDays(5).toString());
        body.put("difficulty", 1);
        Map<String, Object> order = data(post(doctorToken, "/api/orders", body));
        Long oid = toLong(order.get("id"));
        Long item = toLong(list(order, "toothItems").get(0).get("id"));

        // 取模气泡异常上报 → 客服处理
        Map<String, Object> exc = data(post(doctorToken, "/api/orders/" + oid + "/exceptions",
                Map.of("type", "IMPRESSION_BUBBLE", "description", "模型 36 远中气泡", "toothItemId", item)));
        Long excId = toLong(exc.get("id"));
        Map<String, Object> handled = data(post(serviceToken, "/api/exceptions/" + excId + "/handle",
                Map.of("resolution", "诊所重新取模后快递至技工所，已收到", "resolved", true)));
        assertEquals("RESOLVED", handled.get("status"));

        // 患者加急
        Map<String, Object> rushed = data(post(doctorToken, "/api/orders/" + oid + "/rush",
                Map.of("reason", "患者出差，需提前试戴")));
        assertEquals(Boolean.TRUE, rushed.get("rush"));

        // 快速走完工序
        data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", null, null, null, null)));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", null, null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", null, null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", null, null, null, null)));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", null, null, null, null)));

        // 发货 + 延误签收 → 试戴顺延 + 自动登记配送延误异常
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        Long shipmentId = toLong(shipment.get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "LATE", "signedByName", "陈前台", "delayReason", "暴雨高速封路")));
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + oid));
        assertTrue(list(detail, "exceptions").stream().anyMatch(e -> "DELIVERY_DELAY".equals(e.get("type"))));

        // 试戴密合 → 完成
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item);
        fit.put("result", "FIT");
        fit.put("experienceScore", 4);
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins", fit));

        // 结算：300×1×1.0 + 加急 300×0.3=90 − 延误扣罚 300×0.05=15 → 375
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + oid + "/settlement", null));
        assertMoney("300.00", s.get("baseAmount"));
        assertMoney("90.00", s.get("rushFee"));
        assertMoney("15.00", s.get("deliveryPenalty"));
        assertMoney("375.00", s.get("totalAmount"));
    }

    // ---------- 第三场景：色号争议处理（重新制作） ----------

    @Test
    @Order(10)
    void shadeDisputeWithRemakeFlow() {
        // 诊所下单（烤瓷冠 21，色号 VITA A1）
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0003");
        body.put("prosthesisType", "PFM_CROWN");
        body.put("impressionMethod", "TRADITIONAL");
        body.put("toothPositions", List.of("21"));
        body.put("shade", "VITA A1");
        body.put("expectedTryInDate", LocalDate.now().plusDays(3).toString());
        body.put("difficulty", 1);
        Map<String, Object> order = data(post(doctorToken, "/api/orders", body));
        Long oid = toLong(order.get("id"));
        Long item = toLong(list(order, "toothItems").get(0).get("id"));

        // 饰瓷瓷粉批次（原材料批次）
        Map<String, Object> porc = list(data(get(labAdminToken, "/api/inventory/materials")), "$data").stream()
                .filter(m -> "PORC".equals(m.get("code"))).findFirst().orElseThrow();
        Long porcMaterialId = toLong(porc.get("id"));
        Long oldPorcBatch = toLong(list(porc, "batches").get(0).get("id"));
        // 新材料批次入库（重新制作保留新材料批次）
        Map<String, Object> newBatch = data(post(labAdminToken,
                "/api/inventory/materials/" + porcMaterialId + "/batches",
                Map.of("batchNo", "B20260910", "qty", 100)));
        Long newPorcBatch = toLong(newBatch.get("id"));

        // 走完八道工序（扫描设计与质检环节留下取色/对比照片，烧结使用原饰瓷批次）
        data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", "模型接收", null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", "比色取色照片已归档", null, null, List.of("shade_photo_21.jpg"))));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm",
                Map.of("note", "比色板VITA A1，医生确认色号")));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", "排牙完成", null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", "烧结完成", oldPorcBatch, "5", null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", "抛光完成", null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", "质检合格", null, null, List.of("qc_21.jpg"))));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", "打包待发", null, null, null)));

        // 发货签收，首次试戴密合
        Long shipId = toLong(data(post(receiverToken, "/api/orders/" + oid + "/shipments", null)).get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipId + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins",
                Map.of("toothItemId", item, "result", "FIT", "experienceScore", 4)));

        // 1) 患者事后认为颜色与邻牙不一致 → 医生发起色号争议
        Map<String, Object> opened = data(post(doctorToken, "/api/orders/" + oid + "/shade-disputes",
                Map.of("toothItemId", item, "patientClaim", "患者戴牙后反馈比邻牙偏黄，要求核对",
                        "claimedShade", "VITA A2")));
        Long disputeId = toLong(opened.get("id"));
        assertEquals("OPEN", opened.get("status"));
        assertEquals("21", opened.get("toothPosition"));
        // 自动登记色号争议异常，医生端订单详情同步可见
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + oid));
        assertTrue(list(detail, "exceptions").stream().anyMatch(e -> "SHADE_DISPUTE".equals(e.get("type"))));
        assertEquals(1, list(detail, "shadeDisputes").size());
        // 未完成制作的牙位不能发起争议（另建订单快速校验）→ 已由 currentStage 校验，此处校验重复发起被拒
        ResponseEntity<Map> duplicate = postRaw(doctorToken, "/api/orders/" + oid + "/shade-disputes",
                Map.of("toothItemId", item, "patientClaim", "重复发起"));
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.getStatusCode());

        // 2) 服务调取取色照片、医生确认记录、材料批次
        Map<String, Object> evidence = data(post(serviceToken,
                "/api/shade-disputes/" + disputeId + "/collect-evidence",
                Map.of("shadePhotos", List.of("shade_guide_21.jpg"))));
        assertEquals("EVIDENCE_COLLECTED", evidence.get("status"));
        List<?> evidencePhotos = list(evidence, "shadePhotos");
        assertTrue(evidencePhotos.contains("shade_photo_21.jpg"));
        assertTrue(evidencePhotos.contains("qc_21.jpg"));
        assertTrue(evidencePhotos.contains("shade_guide_21.jpg"));
        assertTrue(String.valueOf(evidence.get("doctorConfirmRecord")).contains("比色板VITA A1"));
        assertTrue(String.valueOf(evidence.get("materialBatchRecord")).contains("B20260905"));
        // 医生端不能自行取证
        assertEquals(HttpStatus.FORBIDDEN, postRaw(doctorToken,
                "/api/shade-disputes/" + disputeId + "/collect-evidence", Map.of()).getStatusCode());

        // 3) 自然光复拍（医生在自然光下复拍义齿与邻牙对比）
        Map<String, Object> reshot = data(post(doctorToken,
                "/api/shade-disputes/" + disputeId + "/reshoot",
                Map.of("photos", List.of("natural_21_1.jpg", "natural_21_2.jpg"),
                        "note", "上午10点窗边自然光，21较邻牙偏黄半个色阶")));
        assertEquals("RESHOT", reshot.get("status"));
        assertEquals(2, list(reshot, "naturalLightPhotos").size());

        // 4) 技工所管理员判定重新制作：原件状态、费用承担单独记录，保留原义齿去向与新材料批次
        Map<String, Object> decided = data(post(labAdminToken,
                "/api/shade-disputes/" + disputeId + "/decide", Map.of(
                        "remake", true,
                        "targetShade", "VITA A2",
                        "originalCondition", "原冠完好无崩瓷，仅颜色偏黄",
                        "originalDisposition", "RETURNED_TO_LAB",
                        "costResponsibility", "LAB",
                        "remakeCost", 150,
                        "newMaterialBatchId", newPorcBatch)));
        assertEquals("REWORK_DECIDED", decided.get("status"));
        assertEquals("RETURNED_TO_LAB", decided.get("originalDisposition"));
        assertMoney("150.00", decided.get("remakeCost"));
        assertMoney("150.00", decided.get("labShare"));
        assertMoney("0.00", decided.get("clinicShare"));
        assertEquals("B20260910", decided.get("newMaterialBatchNo"));
        Long shadeReworkId = toLong(decided.get("reworkId"));
        assertNotNull(shadeReworkId);
        // 牙位打回生产线
        detail = data(get(doctorToken, "/api/orders/" + oid));
        assertEquals("IN_PRODUCTION", detail.get("status"));

        // 5) 重新制作：重做烧结使用新材料批次
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", "重做模型接收", null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", "按A2重新比色设计", null, null, List.of("shade_photo_21_v2.jpg"))));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm",
                Map.of("note", "确认按VITA A2重做")));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", "重做排牙", null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", "新材料批次重做烧结", newPorcBatch, "5", null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", "重做抛光", null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", "重做件质检颜色与邻牙一致", null, null, List.of("qc_21_v2.jpg"))));

        // 返工记录同步质检：重做件通过质检 → 返工单自动关闭、争议单推进
        Map<String, Object> afterQc = data(get(qcToken, "/api/shade-disputes/" + disputeId));
        assertEquals("REMAKE_QC_PASSED", afterQc.get("status"));
        assertEquals("周质检", afterQc.get("remakeQcBy"));
        Map<String, Object> rework = list(data(get(doctorToken, "/api/orders/" + oid + "/reworks")), "$data")
                .stream().filter(r -> shadeReworkId.equals(toLong(r.get("id")))).findFirst().orElseThrow();
        assertEquals("COMPLETED", rework.get("status"));
        assertEquals("LAB", rework.get("responsibility"));
        assertTrue(String.valueOf(rework.get("reason")).contains("色号争议"));

        // 6) 重做件配送、患者试戴反馈回到同一牙位档案
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", "重做件打包", null, null, null)));
        Long ship2Id = toLong(data(post(receiverToken, "/api/orders/" + oid + "/shipments",
                Map.of("itemIds", List.of(item)))).get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + ship2Id + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        Map<String, Object> remakeFit = data(post(doctorToken, "/api/orders/" + oid + "/try-ins",
                Map.of("toothItemId", item, "result", "FIT",
                        "feedback", "新色号与邻牙一致，患者满意", "experienceScore", 5)));
        Long remakeTryInId = toLong(remakeFit.get("id"));

        Map<String, Object> afterFit = data(get(doctorToken, "/api/shade-disputes/" + disputeId));
        assertEquals("REMAKE_FITTED", afterFit.get("status"));
        assertEquals(remakeTryInId, toLong(afterFit.get("remakeTryInId")));
        // 试戴反馈挂在同一牙位档案下（牙位看板可见全部工序与试戴）
        Map<String, Object> board = data(get(doctorToken, "/api/orders/" + oid + "/tooth-board"));
        Map<String, Object> tooth21 = list(board, "teeth").stream()
                .filter(t -> "21".equals(t.get("toothPosition"))).findFirst().orElseThrow();
        assertEquals(1, list(tooth21, "shadeDisputes").size());
        assertTrue(list(tooth21, "stages").size() >= 16);

        // 7) 医生复核后关闭
        Map<String, Object> reviewed = data(post(doctorToken,
                "/api/shade-disputes/" + disputeId + "/doctor-review",
                Map.of("note", "医生复核：重做件颜色与邻牙一致，患者确认，关闭争议")));
        assertEquals("DOCTOR_REVIEWED", reviewed.get("status"));
        // 质检主管复核前，质检员无权归档
        assertEquals(HttpStatus.FORBIDDEN, postRaw(qcToken,
                "/api/shade-disputes/" + disputeId + "/qc-archive",
                Map.of("qcConclusion", "COLOR_MATCHED")).getStatusCode());

        // 8) 质检主管复核，质检结论归档，色号争议异常同步关闭
        Map<String, Object> archived = data(post(qcMgrToken,
                "/api/shade-disputes/" + disputeId + "/qc-archive",
                Map.of("qcConclusion", "COLOR_DEVIATION_CONFIRMED",
                        "note", "原义齿确有半阶色差，重做件合格，费用技工所承担")));
        assertEquals("ARCHIVED", archived.get("status"));
        assertEquals("COLOR_DEVIATION_CONFIRMED", archived.get("qcConclusion"));
        assertEquals("蒋质管", archived.get("qcReviewedBy"));
        assertNotNull(archived.get("archivedAt"));
        assertTrue(list(data(get(doctorToken, "/api/orders/" + oid)), "exceptions").stream()
                .filter(e -> "SHADE_DISPUTE".equals(e.get("type")))
                .allMatch(e -> "RESOLVED".equals(e.get("status"))));
        // 已归档后工作台不再出现
        assertTrue(list(data(get(qcMgrToken, "/api/shade-disputes/open")), "$data").stream()
                .noneMatch(d -> disputeId.equals(toLong(d.get("id")))));

        // 9) 结算联动：技工所承担重做费 150 单独扣减（300 − 150 = 150）
        detail = data(get(doctorToken, "/api/orders/" + oid));
        assertEquals("COMPLETED", detail.get("status"));
        Map<String, Object> st = data(post(financeToken, "/api/orders/" + oid + "/settlement", null));
        assertMoney("300.00", st.get("baseAmount"));
        assertMoney("150.00", st.get("reworkLabDeduction"));
        assertMoney("150.00", st.get("totalAmount"));
    }

    // ---------- 第四场景：色号争议处理（不重新制作，沟通后患者接受） ----------

    @Test
    @Order(11)
    void shadeDisputeRejectedBranch() {
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0004");
        body.put("prosthesisType", "ALL_CERAMIC_CROWN");
        body.put("impressionMethod", "DIGITAL_SCAN");
        body.put("toothPositions", List.of("23"));
        body.put("shade", "VITA A3");
        body.put("expectedTryInDate", LocalDate.now().plusDays(4).toString());
        body.put("difficulty", 2);
        Map<String, Object> order = data(post(doctorToken, "/api/orders", body));
        Long oid = toLong(order.get("id"));
        Long item = toLong(list(order, "toothItems").get(0).get("id"));

        data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", null, null, null, List.of("shade_23.jpg"))));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm",
                Map.of("note", "色号A3确认")));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", null, null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", null, zirconiaBatchId, "1", null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", null, null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", null, null, null, List.of("qc_23.jpg"))));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", null, null, null, null)));

        Map<String, Object> dispute = data(post(doctorToken, "/api/orders/" + oid + "/shade-disputes",
                Map.of("toothItemId", item, "patientClaim", "患者觉得灯光下颜色偏白")));
        Long disputeId = toLong(dispute.get("id"));
        data(post(serviceToken, "/api/shade-disputes/" + disputeId + "/collect-evidence", Map.of()));
        data(post(doctorToken, "/api/shade-disputes/" + disputeId + "/reshoot",
                Map.of("photos", List.of("natural_23.jpg"), "note", "自然光下与邻牙无色差")));

        // 判定不重新制作
        Map<String, Object> rejected = data(post(labAdminToken,
                "/api/shade-disputes/" + disputeId + "/decide",
                Map.of("remake", false, "decisionNote", "自然光复核颜色一致，为诊室灯光色温导致，患者接受")));
        assertEquals("REJECTED", rejected.get("status"));
        assertNull(rejected.get("reworkId"));

        // 工作台：医生端可见本诊所争议；他诊所医生不可见
        String doctor2Token = login("doctor2", "doctor123");
        assertTrue(list(data(get(doctorToken, "/api/shade-disputes/open")), "$data").stream()
                .anyMatch(d -> disputeId.equals(toLong(d.get("id")))));
        assertTrue(list(data(get(doctor2Token, "/api/shade-disputes/open")), "$data").stream()
                .noneMatch(d -> disputeId.equals(toLong(d.get("id")))));
        // 他诊所医生不能复核
        assertEquals(HttpStatus.FORBIDDEN, postRaw(doctor2Token,
                "/api/shade-disputes/" + disputeId + "/doctor-review", Map.of()).getStatusCode());

        // 医生复核关闭 → 质检主管复核归档
        data(post(doctorToken, "/api/shade-disputes/" + disputeId + "/doctor-review", Map.of()));
        Map<String, Object> archived = data(post(qcMgrToken,
                "/api/shade-disputes/" + disputeId + "/qc-archive",
                Map.of("qcConclusion", "PATIENT_ACCEPTED", "note", "沟通后患者接受，结论归档")));
        assertEquals("ARCHIVED", archived.get("status"));
        assertEquals("PATIENT_ACCEPTED", archived.get("qcConclusion"));
        assertTrue(list(data(get(doctorToken, "/api/orders/" + oid)), "exceptions").stream()
                .filter(e -> "SHADE_DISPUTE".equals(e.get("type")))
                .allMatch(e -> "RESOLVED".equals(e.get("status"))));
    }

    // ---------- 工具方法 ----------

    private String login(String username, String password) {
        Map<String, Object> resp = data(plain.postForEntity(url("/api/auth/login"),
                Map.of("username", username, "password", password), Map.class));
        return String.valueOf(resp.get("token"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> postRaw(String token, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Auth-Token", token);
        }
        return plain.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body == null ? Map.of() : body, headers), Map.class);
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        ResponseEntity<Map> resp = postRaw(token, path, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                () -> "POST " + path + " -> " + resp.getStatusCode() + " " + resp.getBody());
        return resp;
    }

    private ResponseEntity<Map> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-Token", token);
        ResponseEntity<Map> resp = plain.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                () -> "GET " + path + " -> " + resp.getStatusCode() + " " + resp.getBody());
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        Map<String, Object> body = Objects.requireNonNull(resp.getBody());
        assertEquals(Boolean.TRUE, body.get("success"), () -> "响应失败: " + body);
        Object data = body.get("data");
        if (data instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        // 列表响应：包装一层
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("$data", data);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private Long toLong(Object v) {
        return ((Number) v).longValue();
    }

    private void assertMoney(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
                () -> "金额不符: expected=" + expected + " actual=" + actual);
    }

    private Map<String, Object> stageBody(String stage, String note, Long materialBatchId,
                                          String materialQty, List<String> photos) {
        Map<String, Object> body = new HashMap<>();
        body.put("stage", stage);
        if (note != null) {
            body.put("note", note);
        }
        body.put("estimatedFinishAt", "2026-12-31T23:59:00");
        body.put("finish", true);
        if (materialBatchId != null) {
            body.put("materialBatchId", materialBatchId);
            body.put("materialQty", materialQty);
        }
        if (photos != null) {
            body.put("photos", photos);
        }
        return body;
    }
}
