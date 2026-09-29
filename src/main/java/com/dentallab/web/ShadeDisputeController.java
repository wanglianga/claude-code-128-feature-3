package com.dentallab.web;

import com.dentallab.model.OriginalDentureDisposition;
import com.dentallab.model.Responsibility;
import com.dentallab.model.ShadeDisputeDecision;
import com.dentallab.service.ShadeDisputeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/** 色号争议处理：发起、自然光复拍、判定重做、患者试戴反馈、医生关闭、质检主管复核、结论归档。 */
@Tag(name = "色号争议", description = "义齿颜色与邻牙不一致的争议处理全流程")
@RestController
@RequestMapping("/api")
public class ShadeDisputeController {

    private final ShadeDisputeService disputeService;

    public ShadeDisputeController(ShadeDisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @Operation(summary = "发起色号争议（自动调取取色照片、医生确认记录、材料批次）")
    @PostMapping("/orders/{orderId}/shade-disputes")
    public Map<String, Object> open(HttpServletRequest request, @PathVariable Long orderId,
                                    @RequestBody Map<String, Object> body) {
        return Web.ok(disputeService.open(Web.user(request), orderId,
                Req.longReq(body, "toothItemId", "牙位明细"),
                Req.strReq(body, "patientClaim", "患者主张"),
                Req.str(body, "observedShade"),
                Req.strList(body, "shadeTakingPhotos")));
    }

    @Operation(summary = "自然光下复拍")
    @PostMapping("/shade-disputes/{id}/natural-light-retake")
    public Map<String, Object> retake(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(disputeService.naturalLightRetake(Web.user(request), id,
                Req.strList(body, "naturalLightPhotos"),
                Req.str(body, "note")));
    }

    @Operation(summary = "客服/管理员判定：重新制作（记录原件状态/去向/费用/新材料批次）或维持原义齿")
    @PostMapping("/shade-disputes/{id}/decide")
    public Map<String, Object> decide(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        BigDecimal remakeCost = Req.decimal(body, "remakeCost");
        return Web.ok(disputeService.decide(Web.user(request), id,
                Req.enumReq(body, "decision", ShadeDisputeDecision.class, "争议判定结论"),
                Req.str(body, "remakeShade"),
                Req.str(body, "decisionNote"),
                Req.str(body, "originalDentureStatus"),
                Req.enumVal(body, "originalDentureDisposition", OriginalDentureDisposition.class),
                Req.enumVal(body, "costResponsibility", Responsibility.class),
                remakeCost == null ? BigDecimal.ZERO : remakeCost,
                Req.longVal(body, "newMaterialBatchId")));
    }

    @Operation(summary = "记录患者试戴反馈（回到同一牙位档案）")
    @PostMapping("/shade-disputes/{id}/try-in-feedback")
    public Map<String, Object> tryInFeedback(HttpServletRequest request, @PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        return Web.ok(disputeService.recordPatientTryIn(Web.user(request), id,
                Req.str(body, "feedback"),
                Req.intVal(body, "patientTryInScore"),
                Req.longVal(body, "tryInRecordId")));
    }

    @Operation(summary = "医生复核后关闭")
    @PostMapping("/shade-disputes/{id}/doctor-close")
    public Map<String, Object> doctorClose(HttpServletRequest request, @PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : Req.str(body, "note");
        return Web.ok(disputeService.doctorClose(Web.user(request), id, note));
    }

    @Operation(summary = "质检主管复核")
    @PostMapping("/shade-disputes/{id}/qc-review")
    public Map<String, Object> qcReview(HttpServletRequest request, @PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : Req.str(body, "note");
        return Web.ok(disputeService.qcReview(Web.user(request), id, note));
    }

    @Operation(summary = "质检结论归档")
    @PostMapping("/shade-disputes/{id}/archive")
    public Map<String, Object> archive(HttpServletRequest request, @PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        return Web.ok(disputeService.archive(Web.user(request), id,
                Req.strReq(body, "archiveConclusion", "质检结论")));
    }

    @Operation(summary = "色号争议详情")
    @GetMapping("/shade-disputes/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return Web.ok(disputeService.get(id));
    }

    @Operation(summary = "订单下的色号争议列表（医生端同步查看）")
    @GetMapping("/orders/{orderId}/shade-disputes")
    public Map<String, Object> listByOrder(@PathVariable Long orderId) {
        return Web.ok(disputeService.listByOrder(orderId));
    }

    @Operation(summary = "质检工作台：已同步质检的色号争议返工")
    @GetMapping("/shade-disputes/qc-workbench")
    public Map<String, Object> qcWorkbench(HttpServletRequest request) {
        return Web.ok(disputeService.qcWorkbench(Web.user(request)));
    }
}
