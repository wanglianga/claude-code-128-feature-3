package com.dentallab.config;

import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 初始化演示数据：诊所、各角色账号、材料与批次、价格配置。 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ClinicRepo clinicRepo;
    private final AccountRepo accountRepo;
    private final MaterialRepo materialRepo;
    private final MaterialBatchRepo batchRepo;
    private final PriceConfigRepo priceConfigRepo;

    public DataSeeder(ClinicRepo clinicRepo, AccountRepo accountRepo, MaterialRepo materialRepo,
                      MaterialBatchRepo batchRepo, PriceConfigRepo priceConfigRepo) {
        this.clinicRepo = clinicRepo;
        this.accountRepo = accountRepo;
        this.materialRepo = materialRepo;
        this.batchRepo = batchRepo;
        this.priceConfigRepo = priceConfigRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (accountRepo.count() > 0) {
            return;
        }
        log.info("初始化演示数据...");

        Clinic clinic1 = clinic("晨曦口腔诊所", "陈前台", "021-63000001", "上海市静安区延安中路100号");
        Clinic clinic2 = clinic("博雅口腔门诊", "刘护士", "021-63000002", "上海市徐汇区漕溪北路200号");

        account("doctor1", "doctor123", "王医生", Role.CLINIC_DOCTOR, clinic1);
        account("doctor2", "doctor123", "赵医生", Role.CLINIC_DOCTOR, clinic2);
        account("clinicadmin1", "admin123", "陈院长", Role.CLINIC_ADMIN, clinic1);
        account("receiver", "lab123", "钱前台", Role.LAB_RECEIVER, null);
        account("labadmin", "lab123", "孙厂长", Role.LAB_ADMIN, null);
        account("tech1", "tech123", "张技工", Role.TECHNICIAN, null);
        account("tech2", "tech123", "李技工", Role.TECHNICIAN, null);
        account("qc1", "qc123", "周质检", Role.QC_INSPECTOR, null);
        account("qcmgr1", "qcmgr123", "蒋质管", Role.QC_MANAGER, null);
        account("courier1", "courier123", "吴配送", Role.DELIVERY_COURIER, null);
        account("service1", "service123", "郑客服", Role.CUSTOMER_SERVICE, null);
        account("finance1", "finance123", "冯财务", Role.FINANCE, null);

        material("COCR", "钴铬合金", "g", "12.00", "B20260901", "500", LocalDate.now().plusMonths(12));
        material("ZIRC", "氧化锆瓷块", "块", "85.00", "B20260902", "200", LocalDate.now().plusMonths(18));
        material("RESIN", "树脂基托材料", "g", "3.50", "B20260903", "2000", LocalDate.now().plusMonths(9));
        material("FLEX", "隐形义齿弹性树脂", "g", "6.80", "B20260904", "800", LocalDate.now().plusMonths(9));
        material("PORC", "饰瓷瓷粉", "g", "25.00", "B20260905", "300", LocalDate.now().plusMonths(24));
        material("TITAN", "种植上部钛基台", "个", "320.00", "B20260906", "60", LocalDate.now().plusMonths(36));

        price(ProsthesisType.PFM_CROWN, "300.00", "0.30");
        price(ProsthesisType.ALL_CERAMIC_CROWN, "500.00", "0.30");
        price(ProsthesisType.REMOVABLE_DENTURE, "1200.00", "0.25");
        price(ProsthesisType.FLEXIBLE_DENTURE, "800.00", "0.25");
        price(ProsthesisType.IMPLANT_RESTORATION, "2000.00", "0.35");

        log.info("演示数据初始化完成");
    }

    private Clinic clinic(String name, String contact, String phone, String address) {
        Clinic c = new Clinic();
        c.setName(name);
        c.setContactPerson(contact);
        c.setPhone(phone);
        c.setAddress(address);
        return clinicRepo.save(c);
    }

    private void account(String username, String password, String name, Role role, Clinic clinic) {
        Account a = new Account();
        a.setUsername(username);
        a.setPassword(password);
        a.setDisplayName(name);
        a.setRole(role);
        a.setClinic(clinic);
        accountRepo.save(a);
    }

    private void material(String code, String name, String unit, String price,
                          String batchNo, String qty, LocalDate expiry) {
        Material m = new Material();
        m.setCode(code);
        m.setName(name);
        m.setUnit(unit);
        m.setUnitPrice(new BigDecimal(price));
        materialRepo.save(m);

        MaterialBatch b = new MaterialBatch();
        b.setMaterial(m);
        b.setBatchNo(batchNo);
        b.setTotalQty(new BigDecimal(qty));
        b.setRemainingQty(new BigDecimal(qty));
        b.setExpiryDate(expiry);
        batchRepo.save(b);
    }

    private void price(ProsthesisType type, String basePrice, String rushRate) {
        PriceConfig p = new PriceConfig();
        p.setProsthesisType(type);
        p.setBasePrice(new BigDecimal(basePrice));
        p.setRushRate(new BigDecimal(rushRate));
        priceConfigRepo.save(p);
    }
}
