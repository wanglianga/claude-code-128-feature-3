package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 色号争议：患者认为义齿颜色与邻牙不一致时的专项处理单。
 * 调取取色照片、医生确认记录、材料批次与自然光复拍；
 * 重新制作时单独记录原件状态、费用承担、原义齿去向与新材料批次；
 * 重做件患者试戴反馈回到同一牙位档案，医生复核关闭后由质检主管复核归档。
 */
@Getter
@Setter
@Entity
@Table(name = "shade_dispute", indexes = {
        @Index(name = "idx_shade_order", columnList = "order_id"),
        @Index(name = "idx_shade_item", columnList = "tooth_item_id"),
        @Index(name = "idx_shade_status", columnList = "status")
})
public class ShadeDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DentureOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    /** 患者主诉（认为与邻牙颜色不一致的描述）。 */
    @Column(nullable = false, length = 1000)
    private String patientClaim;

    /** 争议提出时患者认可的目标色号（可空）。 */
    @Column(length = 50)
    private String claimedShade;

    /** 服务调取的原始取色照片（多个以英文逗号分隔）。 */
    @Column(length = 1000)
    private String shadePhotos;

    /** 服务调取的医生确认记录（DOCTOR_CONFIRM 工序快照）。 */
    @Column(length = 1000)
    private String doctorConfirmRecord;

    /** 服务调取的原制作材料批次快照。 */
    @Column(length = 1000)
    private String materialBatchRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collected_by")
    private Account collectedBy;

    private LocalDateTime collectedAt;

    /** 自然光复拍照片（多个以英文逗号分隔）。 */
    @Column(length = 1000)
    private String naturalLightPhotos;

    /** 复拍环境说明（自然光时段、与邻牙对比等）。 */
    @Column(length = 500)
    private String reshootNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reshot_by")
    private Account reshotBy;

    private LocalDateTime reshotAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ShadeDisputeStatus status = ShadeDisputeStatus.OPEN;

    // ---- 重新制作判定 ----

    /** 重新制作时采用的新色号。 */
    @Column(length = 50)
    private String targetShade;

    /** 原义齿状态（决定重做时单独记录）。 */
    @Column(length = 500)
    private String originalCondition;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OriginalDentureDisposition originalDisposition;

    /** 费用承担方（与返工责任共用枚举：诊所/技工所/双方共担）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Responsibility costResponsibility = Responsibility.PENDING;

    /** 重新制作费用（单独记录，不计入普通返工分摊）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal remakeCost = BigDecimal.ZERO;

    /** 诊所承担金额。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal clinicShare = BigDecimal.ZERO;

    /** 技工所承担金额。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal labShare = BigDecimal.ZERO;

    /** 重新制作使用的新材料批次。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_material_batch_id")
    private MaterialBatch newMaterialBatch;

    /** 关联返工单（决定重做时创建，返工记录同步质检）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private Account decidedBy;

    private LocalDateTime decidedAt;

    /** 重新制作判定备注（不重做时记录沟通结论）。 */
    @Column(length = 1000)
    private String decisionNote;

    // ---- 重做件质检 ----

    @Column(length = 1000)
    private String remakeQcNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "remake_qc_by")
    private Account remakeQcBy;

    private LocalDateTime remakeQcAt;

    // ---- 重做件患者试戴（反馈回到同一牙位档案） ----

    /** 关联重做件试戴记录。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "remake_try_in_id")
    private TryInRecord remakeTryIn;

    // ---- 医生复核关闭 ----

    @Column(length = 1000)
    private String doctorReviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_reviewed_by")
    private Account doctorReviewedBy;

    private LocalDateTime doctorReviewedAt;

    // ---- 质检主管复核归档 ----

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ShadeQcConclusion qcConclusion;

    @Column(length = 1000)
    private String qcConclusionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "qc_reviewed_by")
    private Account qcReviewedBy;

    private LocalDateTime archivedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
