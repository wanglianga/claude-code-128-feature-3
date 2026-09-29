#!/usr/bin/env bash
# 全流程验证脚本：下单→接单→八道工序→发货→签收→试戴→返工→重做→结算→月结→色号争议处理
# 前提：服务已启动（默认 http://localhost:8080）
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

say()  { echo -e "\033[1;36m==>\033[0m $*"; }
ok()   { PASS=$((PASS+1)); echo -e "  \033[1;32m✔\033[0m $*"; }
bad()  { FAIL=$((FAIL+1)); echo -e "  \033[1;31m✘ $*\033[0m"; }
check() { # check <描述> <实际值> <期望值>
  if [ "$2" == "$3" ]; then ok "$1 ($2)"; else bad "$1: 期望[$3] 实际[$2]"; fi
}
money() { awk -v v="$1" 'BEGIN{printf "%.2f", v}'; }
checkMoney() { # checkMoney <描述> <实际值> <期望值>
  check "$1" "$(money "$2")" "$(money "$3")"
}

req() { # req <方法> <路径> <token> <json-body>
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-s -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "X-Auth-Token: $token")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

login() { # login <用户名> <密码> -> token
  req POST /api/auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.data.token'
}

say "0. 健康检查与登录"
check "健康检查" "$(req GET /api/health | jq -r '.status')" "UP"
DOCTOR=$(login doctor1 doctor123)
RECEIVER=$(login receiver lab123)
TECH1=$(login tech1 tech123)
TECH2=$(login tech2 tech123)
QC=$(login qc1 qc123)
QCMGR=$(login qcmgr1 qcmgr123)
CADMIN=$(login clinicadmin1 admin123)
LADMIN=$(login labadmin lab123)
SERVICE=$(login service1 service123)
FINANCE=$(login finance1 finance123)
[ -n "$DOCTOR" ] && [ "$DOCTOR" != "null" ] && ok "10 个角色全部登录成功" || bad "登录失败"
check "错误密码被拒绝" "$(req POST /api/auth/login '' '{"username":"doctor1","password":"x"}' | jq -r '.success')" "false"
check "无令牌访问被拒绝" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/orders")" "401"

say "1. 诊所提交全瓷冠订单（牙位 11/12，数字口扫，难度3）"
ORDER=$(req POST /api/orders "$DOCTOR" '{
  "patientCode":"PAT-VERIFY-001","prosthesisType":"ALL_CERAMIC_CROWN","impressionMethod":"DIGITAL_SCAN",
  "toothPositions":["11","12"],"biteRelation":"正常覆合覆盖","shade":"VITA A2",
  "doctorNotes":"邻面接触稍紧","expectedTryInDate":"2026-10-10","materialRequirement":"氧化锆全瓷","difficulty":3}')
OID=$(echo "$ORDER" | jq -r '.data.id')
ONO=$(echo "$ORDER" | jq -r '.data.orderNo')
ITEM1=$(echo "$ORDER" | jq -r '.data.toothItems[0].id')
ITEM2=$(echo "$ORDER" | jq -r '.data.toothItems[1].id')
check "订单创建" "$(echo "$ORDER" | jq -r '.data.status')" "SUBMITTED"
echo "  订单号: $ONO (id=$OID, 牙位项=$ITEM1,$ITEM2)"

req POST "/api/orders/$OID/scan-files" "$DOCTOR" '{"fileName":"pat001.stl","format":"STL","sizeBytes":1048576,"storagePath":"oss://scans/pat001.stl"}' > /dev/null
check "口扫文件登记" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.scanFiles | length')" "1"

say "2. 技工所接单并推进八道工序"
req POST "/api/orders/$OID/accept" "$RECEIVER" > /dev/null
check "接单后状态" "$(req GET "/api/orders/$OID" "$RECEIVER" | jq -r '.data.status')" "ACCEPTED"

ZIRC_BATCH=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .batches[0].id')
STOCK_BEFORE=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .remainingQty')

stage() { # stage <item> <token> <stage> <extra-json>
  req POST "/api/orders/$OID/items/$1/stages" "$2" "{\"stage\":\"$3\",\"estimatedFinishAt\":\"2026-12-31T23:59:00\",\"finish\":true$4}" > /dev/null
}
for ITEM in $ITEM1 $ITEM2; do
  stage "$ITEM" "$RECEIVER" MODEL_RECEIVING ',"note":"模型接收"'
  stage "$ITEM" "$TECH1" SCAN_DESIGN ',"designSummary":"全瓷冠设计","photos":["design.jpg"]'
  req POST "/api/orders/$OID/items/$ITEM/confirm" "$DOCTOR" '{"note":"设计确认"}' > /dev/null
  stage "$ITEM" "$TECH1" TEETH_ARRANGEMENT ''
  stage "$ITEM" "$TECH2" SINTERING ",\"materialBatchId\":$ZIRC_BATCH,\"materialQty\":\"1\",\"photos\":[\"sinter.jpg\"]"
  stage "$ITEM" "$TECH1" POLISHING ''
  stage "$ITEM" "$QC" QC ',"note":"质检合格","photos":["qc.jpg"]'
  stage "$ITEM" "$RECEIVER" DELIVERY ',"note":"已打包"'
done
check "全部工序完成订单状态" "$(req GET "/api/orders/$OID" "$RECEIVER" | jq -r '.data.status')" "QC_PASSED"
STOCK_AFTER=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .remainingQty')
check "材料库存扣减(200-2)" "$(money "$STOCK_AFTER")" "$(money "$(awk -v a="$STOCK_BEFORE" 'BEGIN{print a-2}')")"

say "3. 医生在线批注（设计已冻结 → 自动登记改设计异常）"
req POST "/api/orders/$OID/items/$ITEM1/annotations" "$DOCTOR" '{"content":"切端透明度再低一点"}' > /dev/null
check "批注后自动产生改设计异常" "$(req GET "/api/orders/$OID/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="DESIGN_CHANGE")] | length')" "1"

say "4. 发货与完好签收 → 自动生成试戴安排"
SHIP=$(req POST "/api/orders/$OID/shipments" "$RECEIVER" '')
SHIP_ID=$(echo "$SHIP" | jq -r '.data.id')
check "发货单号生成" "$(echo "$SHIP" | jq -r '.data.trackingNo' | cut -c1-2)" "SF"
req POST "/api/shipments/$SHIP_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
check "签收后订单状态" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.status')" "TRY_IN"
check "试戴安排生成" "$(req GET "/api/orders/$OID/try-ins" "$DOCTOR" | jq -r '.data | length')" "2"

say "5. 牙位11试戴密合，牙位12不密合 → 自动异常+自动返工"
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM1,\"result\":\"FIT\",\"feedback\":\"密合良好\",\"experienceScore\":5}" > /dev/null
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM2,\"result\":\"MISFIT\",\"feedback\":\"边缘不密合\",\"experienceScore\":2}" > /dev/null
check "不密合自动登记异常" "$(req GET "/api/orders/$OID/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="TRY_IN_MISFIT")] | length')" "1"
REWORK_ID=$(req GET "/api/orders/$OID/reworks" "$DOCTOR" | jq -r '.data[0].id')
check "自动创建返工单" "$(req GET "/api/orders/$OID/reworks" "$DOCTOR" | jq -r '.data | length')" "1"

say "6. 订单未完成时结算被拦截"
check "结算拦截(订单未完成)" "$(req POST "/api/orders/$OID/settlement" "$FINANCE" '' | jq -r '.success')" "false"

say "7. 牙位12返工重做 → 重发 → 试戴密合"
req POST "/api/orders/$OID/exceptions" "$SERVICE" "{\"type\":\"REWORK_DISPUTE\",\"description\":\"责任分歧\",\"toothItemId\":$ITEM2}" > /dev/null
for S in MODEL_RECEIVING SCAN_DESIGN; do stage "$ITEM2" "$TECH1" "$S" ''; done
req POST "/api/orders/$OID/items/$ITEM2/confirm" "$DOCTOR" > /dev/null
stage "$ITEM2" "$TECH1" TEETH_ARRANGEMENT ''
stage "$ITEM2" "$TECH2" SINTERING ",\"materialBatchId\":$ZIRC_BATCH,\"materialQty\":\"1\""
stage "$ITEM2" "$TECH1" POLISHING ''
stage "$ITEM2" "$QC" QC ''
stage "$ITEM2" "$RECEIVER" DELIVERY ''
check "返工单自动完成" "$(req GET "/api/orders/$OID/reworks" "$DOCTOR" | jq -r '.data[0].status')" "COMPLETED"
SHIP2=$(req POST "/api/orders/$OID/shipments" "$RECEIVER" "{\"itemIds\":[$ITEM2]}")
SHIP2_ID=$(echo "$SHIP2" | jq -r '.data.id')
req POST "/api/shipments/$SHIP2_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM2,\"result\":\"FIT\",\"experienceScore\":4}" > /dev/null
check "订单完成" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.status')" "COMPLETED"

say "8. 返工责任未判定 → 结算拦截；判定责任（技工所承担200）→ 财务结算"
check "结算拦截(返工责任不清)" "$(req POST "/api/orders/$OID/settlement" "$FINANCE" '' | jq -r '.error' | grep -c '返工责任')" "1"
req POST "/api/reworks/$REWORK_ID/assign-responsibility" "$LADMIN" '{"responsibility":"LAB","reworkCost":200}' > /dev/null
SETTLE=$(req POST "/api/orders/$OID/settlement" "$FINANCE" '')
checkMoney "基础费(500×2)" "$(echo "$SETTLE" | jq -r '.data.baseAmount')" "1000"
checkMoney "难度系数" "$(echo "$SETTLE" | jq -r '.data.difficultyMultiplier')" "1.30"
checkMoney "技工所承担返工扣减" "$(echo "$SETTLE" | jq -r '.data.reworkLabDeduction')" "200"
checkMoney "应付总额(1300-200)" "$(echo "$SETTLE" | jq -r '.data.totalAmount')" "1100"

say "9. 月结：未复核返工 → 确认被拒；复核后确认成功"
YM=$(date +%Y-%m)
STMT=$(req POST /api/statements/generate "$FINANCE" "{\"clinicId\":1,\"yearMonth\":\"$YM\"}")
STMT_ID=$(echo "$STMT" | jq -r '.data.id')
check "月结单含未复核返工" "$(echo "$STMT" | jq -r '.data.unreviewedReworkCount')" "1"
check "未复核时确认被拒" "$(req POST "/api/statements/$STMT_ID/confirm" "$CADMIN" '' | jq -r '.error' | grep -c '复核')" "1"
req POST "/api/reworks/$REWORK_ID/clinic-review" "$CADMIN" > /dev/null
check "复核后月结确认" "$(req POST "/api/statements/$STMT_ID/confirm" "$CADMIN" '' | jq -r '.data.status')" "CONFIRMED"

say "10. 按牙位看板 / 技工绩效 / 加急+延误场景"
check "牙位12看板含返工责任" "$(req GET "/api/orders/$OID/tooth-board" "$LADMIN" | jq -r '[.data.teeth[] | select(.toothPosition=="12") | .reworks[0].responsibility][0]')" "LAB"
check "技工绩效有工序记录" "$(req GET /api/performance/technicians "$LADMIN" | jq -r '[.data[] | select(.technicianName=="张技工") | .stagesCompleted][0] > 0')" "true"

ORDER2=$(req POST /api/orders "$DOCTOR" '{"patientCode":"PAT-VERIFY-002","prosthesisType":"PFM_CROWN","impressionMethod":"TRADITIONAL","toothPositions":["36"],"shade":"VITA B1","expectedTryInDate":"2026-10-05","difficulty":1}')
OID2=$(echo "$ORDER2" | jq -r '.data.id')
ITEM3=$(echo "$ORDER2" | jq -r '.data.toothItems[0].id')
req POST "/api/orders/$OID2/rush" "$DOCTOR" '{"reason":"患者出差加急"}' > /dev/null
EXC=$(req POST "/api/orders/$OID2/exceptions" "$DOCTOR" "{\"type\":\"IMPRESSION_BUBBLE\",\"description\":\"36远中气泡\",\"toothItemId\":$ITEM3}")
EXC_ID=$(echo "$EXC" | jq -r '.data.id')
check "取模气泡异常上报" "$(echo "$EXC" | jq -r '.data.status')" "OPEN"
check "客服处理异常" "$(req POST "/api/exceptions/$EXC_ID/handle" "$SERVICE" '{"resolution":"重新取模","resolved":true}' | jq -r '.data.status')" "RESOLVED"
req POST "/api/orders/$OID2/accept" "$RECEIVER" > /dev/null
stage2() { req POST "/api/orders/$OID2/items/$ITEM3/stages" "$2" "{\"stage\":\"$1\",\"finish\":true}" > /dev/null; }
stage2 MODEL_RECEIVING "$RECEIVER"; stage2 SCAN_DESIGN "$TECH1"
req POST "/api/orders/$OID2/items/$ITEM3/confirm" "$DOCTOR" > /dev/null
stage2 TEETH_ARRANGEMENT "$TECH1"; stage2 SINTERING "$TECH2"; stage2 POLISHING "$TECH1"; stage2 QC "$QC"; stage2 DELIVERY "$RECEIVER"
SHIP3=$(req POST "/api/orders/$OID2/shipments" "$RECEIVER" '')
SHIP3_ID=$(echo "$SHIP3" | jq -r '.data.id')
req POST "/api/shipments/$SHIP3_ID/sign" "$CADMIN" '{"signResult":"LATE","signedByName":"陈前台","delayReason":"暴雨"}' > /dev/null
check "延误签收自动登记异常" "$(req GET "/api/orders/$OID2/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="DELIVERY_DELAY")] | length')" "1"
req POST "/api/orders/$OID2/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM3,\"result\":\"FIT\",\"experienceScore\":4}" > /dev/null
SETTLE2=$(req POST "/api/orders/$OID2/settlement" "$FINANCE" '')
checkMoney "加急费(300×0.3)" "$(echo "$SETTLE2" | jq -r '.data.rushFee')" "90"
checkMoney "延误扣罚(300×0.05)" "$(echo "$SETTLE2" | jq -r '.data.deliveryPenalty')" "15"
checkMoney "加急单应付(300+90-15)" "$(echo "$SETTLE2" | jq -r '.data.totalAmount')" "375"

say "11. 色号争议处理：取证→自然光复拍→重做（原件去向/费用/新材料批次）→试戴回牙位→医生关闭→质管归档"
ORDER4=$(req POST /api/orders "$DOCTOR" '{"patientCode":"PAT-VERIFY-004","prosthesisType":"PFM_CROWN","impressionMethod":"TRADITIONAL","toothPositions":["21"],"shade":"VITA A1","expectedTryInDate":"2026-10-08","difficulty":1}')
OID4=$(echo "$ORDER4" | jq -r '.data.id')
ITEM4=$(echo "$ORDER4" | jq -r '.data.toothItems[0].id')
PORC_MAT=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="PORC") | .id')
PORC_OLD=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="PORC") | .batches[0].id')
PORC_NEW=$(req POST "/api/inventory/materials/$PORC_MAT/batches" "$LADMIN" '{"batchNo":"B20260920","qty":80}' | jq -r '.data.id')

req POST "/api/orders/$OID4/accept" "$RECEIVER" > /dev/null
stage4() { req POST "/api/orders/$OID4/items/$ITEM4/stages" "$2" "{\"stage\":\"$1\",\"estimatedFinishAt\":\"2026-12-31T23:59:00\",\"finish\":true$3}" > /dev/null; }
stage4 MODEL_RECEIVING "$RECEIVER" ''
stage4 SCAN_DESIGN "$TECH1" ',"photos":["shade_photo_21.jpg"]'
req POST "/api/orders/$OID4/items/$ITEM4/confirm" "$DOCTOR" '{"note":"比色板VITA A1，医生确认色号"}' > /dev/null
stage4 TEETH_ARRANGEMENT "$TECH1" ''
stage4 SINTERING "$TECH2" ",\"materialBatchId\":$PORC_OLD,\"materialQty\":\"5\""
stage4 POLISHING "$TECH1" ''
stage4 QC "$QC" ',"photos":["qc_21.jpg"]'
stage4 DELIVERY "$RECEIVER" ''
SHIP4=$(req POST "/api/orders/$OID4/shipments" "$RECEIVER" '')
SHIP4_ID=$(echo "$SHIP4" | jq -r '.data.id')
req POST "/api/shipments/$SHIP4_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
req POST "/api/orders/$OID4/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM4,\"result\":\"FIT\",\"experienceScore\":4}" > /dev/null

# 发起争议 → 自动登记色号争议异常，医生端订单详情同步可见
DISPUTE=$(req POST "/api/orders/$OID4/shade-disputes" "$DOCTOR" "{\"toothItemId\":$ITEM4,\"patientClaim\":\"患者反馈21比邻牙偏黄\",\"claimedShade\":\"VITA A2\"}")
DISP_ID=$(echo "$DISPUTE" | jq -r '.data.id')
check "争议单创建状态" "$(echo "$DISPUTE" | jq -r '.data.status')" "OPEN"
check "自动登记色号争议异常" "$(req GET "/api/orders/$OID4/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="SHADE_DISPUTE")] | length')" "1"
check "订单详情同步争议单" "$(req GET "/api/orders/$OID4" "$DOCTOR" | jq -r '.data.shadeDisputes | length')" "1"
check "重复发起被拒" "$(req POST "/api/orders/$OID4/shade-disputes" "$DOCTOR" "{\"toothItemId\":$ITEM4,\"patientClaim\":\"重复\"}" | jq -r '.success')" "false"

# 调取取色照片/医生确认记录/材料批次
EV=$(req POST "/api/shade-disputes/$DISP_ID/collect-evidence" "$SERVICE" '{"shadePhotos":["shade_guide_21.jpg"]}')
check "取证后状态" "$(echo "$EV" | jq -r '.data.status')" "EVIDENCE_COLLECTED"
check "取证含档案取色照片" "$(echo "$EV" | jq -r '.data.shadePhotos | index("shade_photo_21.jpg") != null')" "true"
check "取证含医生确认记录" "$(echo "$EV" | jq -r '.data.doctorConfirmRecord | contains("比色板VITA A1")')" "true"
check "取证含材料批次记录" "$(echo "$EV" | jq -r '.data.materialBatchRecord | contains("B20260905")')" "true"
check "医生无权取证" "$(req POST "/api/shade-disputes/$DISP_ID/collect-evidence" "$DOCTOR" '{}' | jq -r '.success')" "false"

# 自然光复拍
RS=$(req POST "/api/shade-disputes/$DISP_ID/reshoot" "$DOCTOR" '{"photos":["natural_21_1.jpg","natural_21_2.jpg"],"note":"自然光下半阶偏差"}')
check "复拍后状态" "$(echo "$RS" | jq -r '.data.status')" "RESHOT"
check "复拍照片数" "$(echo "$RS" | jq -r '.data.naturalLightPhotos | length')" "2"

# 判定重新制作：原件状态与费用单独记录，保留原义齿去向与新材料批次
DC=$(req POST "/api/shade-disputes/$DISP_ID/decide" "$LADMIN" "{\"remake\":true,\"targetShade\":\"VITA A2\",\"originalCondition\":\"原冠完好仅偏黄\",\"originalDisposition\":\"RETURNED_TO_LAB\",\"costResponsibility\":\"LAB\",\"remakeCost\":150,\"newMaterialBatchId\":$PORC_NEW}")
check "重做判定状态" "$(echo "$DC" | jq -r '.data.status')" "REWORK_DECIDED"
check "原义齿去向记录" "$(echo "$DC" | jq -r '.data.originalDisposition')" "RETURNED_TO_LAB"
check "费用承担方记录" "$(echo "$DC" | jq -r '.data.costResponsibility')" "LAB"
checkMoney "重做费用单独记录" "$(echo "$DC" | jq -r '.data.remakeCost')" "150"
check "新材料批次保留" "$(echo "$DC" | jq -r '.data.newMaterialBatchNo')" "B20260920"
DISP_REWORK=$(echo "$DC" | jq -r '.data.reworkId')
check "同步创建返工单" "$(req GET "/api/orders/$OID4/reworks" "$DOCTOR" | jq -r --arg id "$DISP_REWORK" '[.data[] | select(.id==($id|tonumber))] | length')" "1"

# 重做全工序（烧结使用新材料批次）
stage4 MODEL_RECEIVING "$RECEIVER" ''
stage4 SCAN_DESIGN "$TECH1" ',"photos":["shade_photo_21_v2.jpg"]'
req POST "/api/orders/$OID4/items/$ITEM4/confirm" "$DOCTOR" '{"note":"确认按A2重做"}' > /dev/null
stage4 TEETH_ARRANGEMENT "$TECH1" ''
stage4 SINTERING "$TECH2" ",\"materialBatchId\":$PORC_NEW,\"materialQty\":\"5\""
stage4 POLISHING "$TECH1" ''
stage4 QC "$QC" ',"note":"重做件颜色与邻牙一致","photos":["qc_21_v2.jpg"]'
check "返工记录同步质检后争议单推进" "$(req GET "/api/shade-disputes/$DISP_ID" "$QC" | jq -r '.data.status')" "REMAKE_QC_PASSED"
check "返工单质检后自动关闭" "$(req GET "/api/orders/$OID4/reworks" "$DOCTOR" | jq -r --arg id "$DISP_REWORK" '.data[] | select(.id==($id|tonumber)) | .status')" "COMPLETED"
stage4 DELIVERY "$RECEIVER" ''
SHIP5=$(req POST "/api/orders/$OID4/shipments" "$RECEIVER" "{\"itemIds\":[$ITEM4]}")
SHIP5_ID=$(echo "$SHIP5" | jq -r '.data.id')
req POST "/api/shipments/$SHIP5_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
TRY5=$(req POST "/api/orders/$OID4/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM4,\"result\":\"FIT\",\"feedback\":\"新色号与邻牙一致\",\"experienceScore\":5}")
TRY5_ID=$(echo "$TRY5" | jq -r '.data.id')
check "试戴反馈回到同一牙位档案" "$(req GET "/api/shade-disputes/$DISP_ID" "$DOCTOR" | jq -r '.data.remakeTryInId')" "$TRY5_ID"
check "试戴后争议单状态" "$(req GET "/api/shade-disputes/$DISP_ID" "$DOCTOR" | jq -r '.data.status')" "REMAKE_FITTED"
check "牙位看板含色号争议" "$(req GET "/api/orders/$OID4/tooth-board" "$DOCTOR" | jq -r '.data.teeth[0].shadeDisputes | length')" "1"

# 医生复核关闭 → 质检员无权归档 → 质检主管复核归档
req POST "/api/shade-disputes/$DISP_ID/doctor-review" "$DOCTOR" '{"note":"医生复核颜色一致，患者确认，关闭"}' > /dev/null
check "医生复核后状态" "$(req GET "/api/shade-disputes/$DISP_ID" "$DOCTOR" | jq -r '.data.status')" "DOCTOR_REVIEWED"
check "质检员无权归档" "$(req POST "/api/shade-disputes/$DISP_ID/qc-archive" "$QC" '{"qcConclusion":"COLOR_MATCHED"}' | jq -r '.success')" "false"
AR=$(req POST "/api/shade-disputes/$DISP_ID/qc-archive" "$QCMGR" '{"qcConclusion":"COLOR_DEVIATION_CONFIRMED","note":"原件半阶色差，重做合格"}')
check "质管归档状态" "$(echo "$AR" | jq -r '.data.status')" "ARCHIVED"
check "质检结论" "$(echo "$AR" | jq -r '.data.qcConclusion')" "COLOR_DEVIATION_CONFIRMED"
check "归档后色号异常同步关闭" "$(req GET "/api/orders/$OID4/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="SHADE_DISPUTE")] | all(.status=="RESOLVED")')" "true"
check "归档后退出工作台" "$(req GET /api/shade-disputes/open "$QCMGR" | jq -r --arg id "$DISP_ID" '[.data[] | select(.id==($id|tonumber))] | length')" "0"
DOCTOR2=$(login doctor2 doctor123)
check "他诊所医生工作台不可见" "$(req GET /api/shade-disputes/open "$DOCTOR2" | jq -r --arg id "$DISP_ID" '[.data[] | select(.id==($id|tonumber))] | length')" "0"

# 结算联动：技工所承担重做费150扣减（300-150=150）
SETTLE4=$(req POST "/api/orders/$OID4/settlement" "$FINANCE" '')
checkMoney "色号争议单基础费" "$(echo "$SETTLE4" | jq -r '.data.baseAmount')" "300"
checkMoney "色号争议技工所承担扣减" "$(echo "$SETTLE4" | jq -r '.data.reworkLabDeduction')" "150"
checkMoney "色号争议单应付(300-150)" "$(echo "$SETTLE4" | jq -r '.data.totalAmount')" "150"

echo ""
echo "==============================================="
echo -e "验证结果: \033[1;32m通过 $PASS\033[0m 项, \033[1;31m失败 $FAIL\033[0m 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
