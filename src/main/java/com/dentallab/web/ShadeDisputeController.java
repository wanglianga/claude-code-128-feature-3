package com.dentallab.web;

import com.dentallab.model.OriginalDentureDisposition;
import com.dentallab.model.Responsibility;
import com.dentallab.model.ShadeQcConclusion;
import com.dentallab.service.ShadeDisputeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 色号争议处理：发起→取证→自然光复拍→重做判定→重做试戴→医生复核关闭→质检主管复核归档。 */
@RestController
@RequestMapping("/api")
public class ShadeDisputeController {

    private final ShadeDisputeService shadeDisputeService;

    public ShadeDisputeController(ShadeDisputeService shadeDisputeService) {
        this.shadeDisputeService = shadeDisputeService;
    }

    /** 发起色号争议（患者认为义齿颜色与邻牙不一致），自动登记 SHADE_DISPUTE 异常。 */
    @PostMapping("/orders/{orderId}/shade-disputes")
    public Map<String, Object> open(HttpServletRequest request, @PathVariable Long orderId,
                                    @RequestBody Map<String, Object> body) {
        return Web.ok(shadeDisputeService.open(Web.user(request), orderId,
                Req.longReq(body, "toothItemId", "牙位明细"),
                Req.strReq(body, "patientClaim", "患者主诉"),
                Req.str(body, "claimedShade")));
    }

    /** 服务调取取色照片、医生确认记录、材料批次（可在 shadePhotos 补充取色照片）。 */
    @PostMapping("/shade-disputes/{id}/collect-evidence")
    public Map<String, Object> collectEvidence(HttpServletRequest request, @PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return Web.ok(shadeDisputeService.collectEvidence(Web.user(request), id,
                body == null ? java.util.List.of() : Req.strList(body, "shadePhotos")));
    }

    /** 自然光下复拍义齿与邻牙对比照片。 */
    @PostMapping("/shade-disputes/{id}/reshoot")
    public Map<String, Object> reshoot(HttpServletRequest request, @PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        return Web.ok(shadeDisputeService.reshoot(Web.user(request), id,
                Req.strList(body, "photos"), Req.str(body, "note")));
    }

    /** 重新制作判定：重做需记录新色号、原义齿状态/去向、费用承担与新材料批次；不重做需沟通结论。 */
    @PostMapping("/shade-disputes/{id}/decide")
    public Map<String, Object> decide(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(shadeDisputeService.decide(Web.user(request), id,
                Req.bool(body, "remake", false),
                Req.str(body, "targetShade"),
                Req.str(body, "originalCondition"),
                Req.enumVal(body, "originalDisposition", OriginalDentureDisposition.class),
                Req.enumVal(body, "costResponsibility", Responsibility.class),
                Req.decimal(body, "remakeCost"),
                Req.longVal(body, "newMaterialBatchId"),
                Req.str(body, "decisionNote")));
    }

    /** 医生复核后关闭争议。 */
    @PostMapping("/shade-disputes/{id}/doctor-review")
    public Map<String, Object> doctorReview(HttpServletRequest request, @PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return Web.ok(shadeDisputeService.doctorReview(Web.user(request), id,
                body == null ? null : Req.str(body, "note")));
    }

    /** 质检主管复核并归档质检结论。 */
    @PostMapping("/shade-disputes/{id}/qc-archive")
    public Map<String, Object> qcArchive(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        return Web.ok(shadeDisputeService.qcArchive(Web.user(request), id,
                Req.enumReq(body, "qcConclusion", ShadeQcConclusion.class, "质检复核结论"),
                Req.str(body, "note")));
    }

    @GetMapping("/shade-disputes/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return Web.ok(shadeDisputeService.get(id));
    }

    @GetMapping("/orders/{orderId}/shade-disputes")
    public Map<String, Object> listByOrder(@PathVariable Long orderId) {
        return Web.ok(shadeDisputeService.listByOrder(orderId));
    }

    /** 色号争议工作台（医生端/质检/客服同步可见，诊所账号仅见本诊所）。 */
    @GetMapping("/shade-disputes/open")
    public Map<String, Object> listOpen(HttpServletRequest request) {
        return Web.ok(shadeDisputeService.listOpen(Web.user(request)));
    }
}
