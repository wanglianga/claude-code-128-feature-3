package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 色号争议处理单：患者认为义齿颜色与邻牙不一致时发起。
 * 归集取色照片、医生确认记录、材料批次与自然光复拍；
 * 若重新制作，原件状态与费用承担单独记录，并保留原义齿去向与新材料批次；
 * 患者试戴反馈回到同一牙位档案，医生复核关闭、质检主管复核、质检结论归档。
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

    /** 争议始终绑定同一牙位档案。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ShadeDisputeStatus status = ShadeDisputeStatus.OPEN;

    /** 患者主张（认为与邻牙颜色不一致的描述）。 */
    @Column(nullable = false, length = 1000)
    private String patientClaim;

    /** 下单色号快照，如 VITA A2。 */
    @Column(length = 50)
    private String orderedShade;

    /** 争议时比对观察到的色号。 */
    @Column(length = 50)
    private String observedShade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by")
    private Account reportedBy;

    @Column(nullable = false)
    private LocalDateTime reportedAt = LocalDateTime.now();

    // ---- 服务调取的证据链 ----

    /** 取色照片（多个英文逗号分隔）。 */
    @Column(length = 1000)
    private String shadeTakingPhotos;

    /** 医生确认记录摘要（确认人、确认意见）。 */
    @Column(length = 1000)
    private String doctorConfirmRecord;

    /** 原始制作材料批次快照（材料名 + 批次号）。 */
    @Column(length = 500)
    private String materialBatchSummary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_batch_id")
    private MaterialBatch materialBatch;

    // ---- 自然光复拍 ----

    /** 自然光下复拍照片（多个英文逗号分隔）。 */
    @Column(length = 1000)
    private String naturalLightPhotos;

    @Column(length = 1000)
    private String naturalLightNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "natural_light_by")
    private Account naturalLightTakenBy;

    private LocalDateTime naturalLightAt;

    // ---- 争议判定 ----

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ShadeDisputeDecision decision;

    /** 重做目标色号。 */
    @Column(length = 50)
    private String remakeShade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private Account decidedBy;

    private LocalDateTime decidedAt;

    @Column(length = 1000)
    private String decisionNote;

    // ---- 重新制作（原件状态与费用承担单独记录） ----

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    /** 原件状态（返工前义齿的成色/损耗描述）。 */
    @Column(length = 1000)
    private String originalDentureStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OriginalDentureDisposition originalDentureDisposition;

    /** 费用承担方。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Responsibility costResponsibility;

    @Column(precision = 12, scale = 2)
    private BigDecimal remakeCost = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal clinicShare = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal labShare = BigDecimal.ZERO;

    /** 重新制作使用的新材料批次。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_material_batch_id")
    private MaterialBatch newMaterialBatch;

    // ---- 患者试戴反馈（回到同一牙位档案） ----

    @Column(length = 1000)
    private String patientTryInFeedback;

    private Integer patientTryInScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "try_in_record_id")
    private TryInRecord tryInRecord;

    private LocalDateTime patientTryInAt;

    // ---- 医生复核关闭 ----

    @Column(nullable = false)
    private boolean doctorClosed = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_closed_by")
    private Account doctorClosedBy;

    private LocalDateTime doctorClosedAt;

    @Column(length = 1000)
    private String doctorCloseNote;

    // ---- 质检主管复核 ----

    @Column(nullable = false)
    private boolean qcReviewed = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "qc_reviewed_by")
    private Account qcReviewedBy;

    private LocalDateTime qcReviewedAt;

    @Column(length = 1000)
    private String qcReviewNote;

    // ---- 质检结论归档 ----

    @Column(nullable = false)
    private boolean archived = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private Account archivedBy;

    private LocalDateTime archivedAt;

    @Column(length = 1000)
    private String archiveConclusion;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
