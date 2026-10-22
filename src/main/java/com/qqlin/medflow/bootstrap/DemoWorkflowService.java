package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.service.ScheduleService;
import com.qqlin.medflow.organization.detail.DoctorDetailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.*;
import java.util.*;

/**
 * Clearly fictional local demo fixtures, never a production business entry point.
 * Historical attendance is inserted at legal historical times without relaxing
 * check-in/start rules. Future reservations use the normal appointment service.
 */
@Service
@ConditionalOnProperty(name={"medflow.demo.accounts.enabled","medflow.demo.workflow.enabled"},havingValue="true")
public class DemoWorkflowService {
    private static final String ROOM_NAME="演示诊室（虚构）";
    private static final List<String> PATIENT_NAMES=List.of("演示·候诊一（虚构）","演示·候诊二（虚构）",
            "演示·已完成一（虚构）","演示·已完成二（虚构）","演示·过号（虚构）","演示·未来预约（虚构）");
    private static final List<DemoDrug> DRUGS=List.of(
            new DemoDrug("DEMO-001","演示药品甲（模拟）","10mg × 12片","盒",new BigDecimal("3.50")),
            new DemoDrug("DEMO-002","演示药品乙（模拟）","100ml","瓶",new BigDecimal("12.80")),
            new DemoDrug("DEMO-003","演示药品丙（模拟）","5g × 6袋","盒",new BigDecimal("5.20")),
            new DemoDrug("DEMO-004","演示药品丁（模拟）","20mg × 10粒","盒",new BigDecimal("18.00")),
            new DemoDrug("DEMO-005","演示药品戊（模拟）","50ml","瓶",new BigDecimal("8.40")));
    private final JdbcTemplate jdbc;
    private final ScheduleService schedules;
    private final AppointmentService appointments;
    private final DoctorDetailService profiles;

    public DemoWorkflowService(JdbcTemplate jdbc,ScheduleService schedules,AppointmentService appointments,DoctorDetailService profiles) {
        this.jdbc=jdbc;this.schedules=schedules;this.appointments=appointments;this.profiles=profiles;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public DemoWorkflowSummary initialize() {
        // Every bootstrap/refresh transaction acquires this same first lock.
        Long adminId=oneLong("SELECT id FROM sys_user WHERE username='admin' AND role='ADMIN' AND status='ENABLED' FOR UPDATE");
        if(adminId==null) throw new IllegalStateException("Enable demo accounts before initializing demo workflow");
        LocalDateTime now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);
        LocalDate today=now.toLocalDate();
        Counts counts=new Counts();
        long owner=requiredLong("SELECT id FROM sys_user WHERE username='patient' AND role='PATIENT' AND status='ENABLED'");
        long doctorUser=requiredLong("SELECT id FROM sys_user WHERE username='doctor' AND role='DOCTOR' AND status='ENABLED'");
        DoctorIdentity doctor=jdbc.query("SELECT id,department_id FROM med_doctor WHERE user_id=? AND status='ENABLED' FOR UPDATE",
                (rs,row)->new DoctorIdentity(rs.getLong("id"),rs.getLong("department_id")),doctorUser).stream().findFirst()
                .orElseThrow(()->new IllegalStateException("Demo doctor profile is missing"));
        long room=ensureRoom(doctor.departmentId());
        // Phase 1: acquire every existing doctor before any room, slot,
        // encounter or drug. Never take a new Doctor lock after a Drug lock.
        List<CatalogueDoctor> catalogue=ensureCatalogueDoctors(doctorUser,today);
        List<Long> rooms=new ArrayList<>();rooms.add(room);
        rooms.addAll(catalogue.stream().map(CatalogueDoctor::roomId).toList());
        for(long roomId:rooms.stream().distinct().sorted().toList())
            requiredLong("SELECT id FROM med_clinic_room WHERE id=? FOR UPDATE",roomId);
        ensurePrimaryPublicProfile(doctor.id(),today);
        long morning=ensureShift("MORNING","DEMO_MORNING","演示上午门诊",LocalTime.of(8,0),LocalTime.of(12,0));
        long afternoon=ensureShift("AFTERNOON","DEMO_AFTERNOON","演示下午门诊",LocalTime.of(13,0),LocalTime.of(17,0));
        long[] patients=new long[PATIENT_NAMES.size()];
        for(int i=0;i<patients.length;i++) patients[i]=ensurePatient(owner,PATIENT_NAMES.get(i),i,counts);
        List<Long> drugs=ensureDrugs(counts);

        for(int day=1;day<=7;day++) {
            ensureFutureSchedule(doctor.id(),room,morning,today.plusDays(day),LocalTime.of(8,0),LocalTime.of(12,0),true,counts);
            ensureFutureSchedule(doctor.id(),room,afternoon,today.plusDays(day),LocalTime.of(13,0),LocalTime.of(17,0),true,counts);
        }
        ensureFutureDraft(doctor.id(),room,morning,today,counts);
        ensureFutureBooking(owner,patients[5],doctor.id(),today.plusDays(1),counts);
        // Phase 2: primary clinical fixtures lock all existing Slots before
        // generating prescriptions. Catalogue history only uses new private
        // fixture slots; every referenced doctor/room is already locked above.
        boolean seededToday=ensureClinicalFixtures(owner,doctorUser,doctor.id(),room,morning,patients,drugs,today,now,counts);
        seedCatalogueSchedulesAndHistory(catalogue,owner,morning,patients[0],drugs,today,now,counts);
        String message=seededToday ? "已准备明确虚构的诊疗示例；未来7天号源和草稿已检查。"
                : "已检查演示数据；同日已生成的数据及您后续操作的状态保持不变。";
        if(now.toLocalTime().isBefore(LocalTime.of(8,0))) message+=" 今日正常上午门诊自08:00开始，候诊示例届时可接诊。";
        for(String warning:counts.warnings) message+=" "+warning;
        return new DemoWorkflowSummary(today,seededToday,counts.schedules,counts.slots,counts.patients,counts.drugs,
                counts.appointments,counts.encounters,counts.prescriptions,counts.payments,counts.skipped,message);
    }

    private long ensureRoom(long department) {
        Long room=oneLong("SELECT id FROM med_clinic_room WHERE department_id=? AND name=?",department,ROOM_NAME);
        if(room!=null) {
            String status=jdbc.queryForObject("SELECT status FROM med_clinic_room WHERE id=?",String.class,room);
            if("ENABLED".equals(status)) return room;
            Long alternative=oneLong("SELECT id FROM med_clinic_room WHERE department_id=? AND status='ENABLED' ORDER BY id LIMIT 1",department);
            if(alternative!=null) return alternative;
            throw new IllegalStateException("Demo clinic room is disabled; enable a room before refreshing demo fixtures");
        }
        return insert("INSERT INTO med_clinic_room(department_id,name,status) VALUES(?,?,'ENABLED')",department,ROOM_NAME);
    }

    /** Phase 1 contains identity/resource preparation only, never prescriptions. */
    private List<CatalogueDoctor> ensureCatalogueDoctors(long primaryDoctorUser,LocalDate today) {
        List<CatalogueDoctor> result=new ArrayList<>();
        String[][] catalogue={
                {"demo.internal","演示内科（虚构）","林医生（虚构）","主治医师（演示）","内科普通门诊与复诊咨询（虚构演示）"},
                {"demo.surgery","演示外科（虚构）","周医生（虚构）","主治医师（演示）","外科普通门诊与术后随访（虚构演示）"},
                {"demo.derm","演示皮肤科（虚构）","陈医生（虚构）","副主任医师（演示）","皮肤科普通门诊与复诊咨询（虚构演示）"}};
        for(int index=0;index<catalogue.length;index++) {
            String[] item=catalogue[index];
            String key="catalogue:v1:"+item[0];
            Long doctor=registryEntity(key);
            if(!registered(key)) {
                // Never claim an unrelated pre-existing login or reset its credentials.
                if(oneLong("SELECT id FROM sys_user WHERE username=?",item[0])!=null) {
                    register(key,today,"SKIPPED",null,"同名登录账号已存在，未绑定或覆盖");
                    continue;
                }
                jdbc.update("""
                        INSERT INTO med_department(name,status) VALUES(?,'ENABLED')
                        ON DUPLICATE KEY UPDATE id=id
                        """,item[1]);
                long department=requiredLong("SELECT id FROM med_department WHERE name=? AND status='ENABLED'",item[1]);
                jdbc.update("""
                        INSERT INTO sys_user(username,password_hash,role,status,token_version)
                        SELECT ?,password_hash,'DOCTOR','ENABLED',0 FROM sys_user WHERE id=?
                        """,item[0],primaryDoctorUser);
                long user=requiredLong("SELECT id FROM sys_user WHERE username=?",item[0]);
                doctor=insert("""
                        INSERT INTO med_doctor(user_id,department_id,name,status,title,specialty,introduction)
                        VALUES(?,?,?,'ENABLED',?,?,?)
                        """,user,department,item[2],item[3],item[4],
                        "【虚构演示】用于验证医生资料、按科室预约及同一患者跨医生的院内病历查看。不是实际医师履历。");
                register(key,today,"DOCTOR",doctor,"明确虚构的科室/医生目录；后续修改保持不变");
            }
            if(doctor==null || oneLong("""
                    SELECT d.id FROM med_doctor d JOIN med_department p ON p.id=d.department_id
                    JOIN sys_user u ON u.id=d.user_id WHERE d.id=? AND d.status='ENABLED'
                    AND p.status='ENABLED' AND u.status='ENABLED' AND u.role='DOCTOR' FOR UPDATE OF d
                    """,doctor)==null) continue;
            long department=requiredLong("SELECT department_id FROM med_doctor WHERE id=?",doctor);
            long room=ensureRoom(department);
            long user=requiredLong("SELECT user_id FROM med_doctor WHERE id=?",doctor);
            result.add(new CatalogueDoctor(index,doctor,user,room));
        }
        return result;
    }

    /** Phase 2: two past visits of the SAME waiting patient, on already locked doctors. */
    private void seedCatalogueSchedulesAndHistory(List<CatalogueDoctor> catalogue,long owner,long morning,long patient,
            List<Long> drugs,LocalDate today,LocalDateTime now,Counts counts) {
        for(CatalogueDoctor item:catalogue) {
            long doctor=item.doctorId(),room=item.roomId();int index=item.index();
            for(int day=1;day<=7;day++) ensureFutureSchedule(doctor,room,morning,today.plusDays(day),
                    LocalTime.of(8,0),LocalTime.of(12,0),true,counts);
            String historyKey="patient-history:v1:"+patient+":"+index;
            if(index>=2 || registered(historyKey)) continue;
            LocalDate day=today.minusDays(index==0?7:3);
            if(overlap(doctor,room,day,LocalTime.of(8,0),LocalTime.of(12,0))!=null) {
                register(historyKey,day,"SKIPPED",null,"已有历史排班，未更改真实记录");continue;
            }
            createMorningFixture(doctor,room,morning,day,counts);
            DemoSlot slot=todaySlots(doctor,day).getFirst();
            requiredLong("SELECT id FROM med_slot WHERE id=? FOR UPDATE",slot.id());
            createClinicalFixture(owner,item.userId(),doctor,patient,slot,index==0?2:3,drugs,now,counts);
            long encounter=requiredLong("SELECT e.id FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id WHERE a.patient_id=? AND a.slot_id=?",patient,slot.id());
            jdbc.update("""
                    UPDATE med_medical_record SET chief_complaint=?,present_illness=?,diagnosis=?,advice=?
                    WHERE encounter_id=?
                    ""","【虚构演示】"+(index==0?"初次内科就诊记录":"外科复诊记录"),
                    "【虚构演示】同一患者在不同日期、不同科室就诊，供当前接诊医生查阅院内历史。",
                    "【虚构演示】"+(index==0?"内科门诊模拟诊断":"外科门诊模拟诊断"),
                    "【虚构演示】本例医嘱与处方仅用于软件流程验证。",encounter);
            register(historyKey,day,"ENCOUNTER",encounter,"同一候诊患者的跨医生只读历史");
        }
    }

    private void ensurePrimaryPublicProfile(long doctor,LocalDate today) {
        String key="public-profile:v1:"+doctor;
        if(registered(key)) return;
        ProfileFields existing=jdbc.query("SELECT name,title,specialty,introduction FROM med_doctor WHERE id=?",
                (rs,row)->new ProfileFields(rs.getString("name"),rs.getString("title"),rs.getString("specialty"),rs.getString("introduction")),doctor).getFirst();
        String title=blank(existing.title())?"全科门诊医师（虚构演示）":null;
        String specialty=blank(existing.specialty())?"【虚构演示】全科普通门诊、常见不适的模拟接诊与复诊流程。":null;
        String introduction=blank(existing.introduction())?"【虚构演示】此档案用于学习预约、接诊、病历和处方收费流程，不代表真实医师资历或医疗服务。":null;
        if(title!=null || specialty!=null || introduction!=null)
            profiles.updateProfile(doctor,existing.name(),title,specialty,introduction);
        register(key,today,"PUBLIC_PROFILE",doctor,"仅首次补充缺失的虚构公开资料，保留管理员维护内容");
    }

    private static boolean blank(String value) {return value==null || value.isBlank();}

    private long ensureShift(String preferred,String fallback,String name,LocalTime start,LocalTime end) {
        Long id=oneLong("""
                SELECT id FROM med_shift_template WHERE code=? AND status='ENABLED' AND start_time=? AND end_time=?
                  AND slot_duration_minutes=30 AND capacity_per_slot=10
                """,preferred,start,end);
        if(id!=null) return id;
        id=oneLong("SELECT id FROM med_shift_template WHERE code=? AND status='ENABLED'",fallback);
        if(id!=null) return id;
        if(oneLong("SELECT id FROM med_shift_template WHERE code=?",fallback)!=null)
            throw new IllegalStateException("Demo shift template was disabled; demo refresh does not re-enable it");
        return insert("""
                INSERT INTO med_shift_template(code,name,start_time,end_time,slot_duration_minutes,capacity_per_slot,status)
                VALUES(?,?,?,?,30,10,'ENABLED')
                """,fallback,name,start,end);
    }

    private long ensurePatient(long owner,String name,int index,Counts counts) {
        String key="patient:"+owner+":"+index;
        Long registered=registryEntity(key);
        if(registered!=null) return registered;
        Long existing=oneLong("SELECT id FROM med_patient WHERE owner_user_id=? AND name=? ORDER BY id LIMIT 1",owner,name);
        long id=existing!=null ? existing : insert("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES(?,?,'UNKNOWN','1995-01-01',?,'SELF')
                """,owner,name,"1380000000"+index);
        if(existing==null) counts.patients++;
        register(key,null,"PATIENT",id,"明确虚构的演示就诊人");
        return id;
    }

    private List<Long> ensureDrugs(Counts counts) {
        List<Long> ids=new ArrayList<>();
        for(DemoDrug drug:DRUGS) {
            Long id=oneLong("SELECT id FROM med_drug WHERE code=?",drug.code());
            if(id==null) {
                id=insert("INSERT INTO med_drug(code,name,specification,unit,unit_price,status) VALUES(?,?,?,?,?,'ENABLED')",
                        drug.code(),drug.name(),drug.specification(),drug.unit(),drug.price());
                counts.drugs++;
            }
            ids.add(id);
        }
        return ids;
    }

    private void ensureFutureSchedule(long doctor,long room,long shift,LocalDate day,LocalTime start,LocalTime end,
            boolean publish,Counts counts) {
        String key="schedule:"+doctor+":"+day+":"+start;
        if(registered(key)) return;
        Long overlap=overlap(doctor,room,day,start,end);
        if(overlap!=null) {
            register(key,day,"SCHEDULE_EXISTING",overlap,"已有排班（包括已取消排班），保留其状态");
            counts.skipped++;
            return;
        }
        long id=schedules.createDraft(new CreateScheduleCommand(doctor,room,shift,day));
        counts.schedules++;
        if(publish) counts.slots+=schedules.publish(id).generatedSlotCount();
        register(key,day,"SCHEDULE",id,publish?"演示已发布排班":"演示待发布草稿");
    }

    private void ensureFutureDraft(long doctor,long room,long shift,LocalDate today,Counts counts) {
        String key="draft:"+doctor+":"+today;
        if(registered(key)) return;
        for(int offset=8;offset<=14;offset++) {
            LocalDate date=today.plusDays(offset);
            if(overlap(doctor,room,date,LocalTime.of(8,0),LocalTime.of(12,0))!=null) continue;
            long id=schedules.createDraft(new CreateScheduleCommand(doctor,room,shift,date));
            register(key,today,"DRAFT",id,"管理员可以发布或取消的未来演示草稿");
            counts.schedules++;
            return;
        }
        register(key,today,"SKIPPED",null,"未来草稿候选日期均存在冲突，未更改原排班");
        counts.skipped++;
    }

    private Long overlap(long doctor,long room,LocalDate day,LocalTime start,LocalTime end) {
        // A cancelled own schedule is also a deliberate user decision: do not recreate it.
        return oneLong("""
                SELECT id FROM med_schedule WHERE work_date=? AND start_time<? AND end_time>?
                AND (doctor_id=? OR (clinic_room_id=? AND status IN ('DRAFT','PUBLISHED')))
                ORDER BY id LIMIT 1
                """,day,end,start,doctor,room);
    }

    private void ensureFutureBooking(long owner,long patient,long doctor,LocalDate day,Counts counts) {
        String key="future-booking:"+doctor+":"+day;
        if(registered(key)) return;
        List<Long> candidates=jdbc.queryForList("""
                SELECT sl.id FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id
                WHERE s.doctor_id=? AND sl.work_date=? AND s.status='PUBLISHED' AND sl.status='OPEN'
                  AND sl.remaining_capacity>0 ORDER BY sl.start_time,sl.id
                """,Long.class,doctor,day);
        for(long slot:candidates) {
            // Reuse the production Slot -> Schedule locks before the normal service.
            var locked=jdbc.query("""
                    SELECT sl.id,sl.remaining_capacity,s.status AS schedule_status,sl.status AS slot_status
                    FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id WHERE sl.id=?
                    FOR UPDATE OF sl FOR SHARE OF s
                    """,(rs,row)->rs.getInt("remaining_capacity")>0 && "PUBLISHED".equals(rs.getString("schedule_status"))
                    && "OPEN".equals(rs.getString("slot_status")),slot);
            if(locked.isEmpty() || !locked.getFirst()) continue;
            if(oneLong("SELECT id FROM med_appointment WHERE patient_id=? AND slot_id=? AND active_flag=1",patient,slot)!=null) continue;
            var created=appointments.create(owner,new CreateAppointmentCommand(patient,slot,"demo-future-"+doctor+"-"+day));
            appointments.confirm(owner,created.appointmentId());
            register(key,day,"APPOINTMENT",created.appointmentId(),"通过正常创建和确认接口生成的未来演示预约");
            counts.appointments++;
            return;
        }
        register(key,day,"SKIPPED",null,"未找到可用未来号源，未改写已有预约");
    }

    private boolean ensureClinicalFixtures(long owner,long doctorUser,long doctor,long room,long morning,long[] patients,
            List<Long> drugs,LocalDate today,LocalDateTime now,Counts counts) {
        String key="clinical:"+doctor+":"+today;
        if(registered(key)) return false;
        String completedKey=key+":completed",queueKey=key+":queue";
        boolean needCompleted=!registered(completedKey),needQueue=!registered(queueKey);
        int before=counts.encounters;
        List<DemoSlot> slots=todaySlots(doctor,today);
        if(slots.isEmpty() && overlap(doctor,room,today,LocalTime.of(8,0),LocalTime.of(12,0))==null) {
            // Historical normal morning fixture: the production create endpoint
            // intentionally continues to reject schedules dated today.
            createMorningFixture(doctor,room,morning,today,counts);
            slots=todaySlots(doctor,today);
        }
        // Do not create completed encounters in the future when starting early
        // in the day. Keep their two sequential visits in a real prior day shift.
        boolean historicalCompletion=needCompleted && !hasTwoPastWindows(doctor,slots,now);
        List<DemoSlot> pastSlots=historicalCompletion?pastClinicalSlots(doctor,room,morning,today,now,counts):List.of();
        List<DemoSlot> allSlots=new ArrayList<>(slots);allSlots.addAll(pastSlots);
        // Lock every candidate slot before any parent schedule, matching cancellation.
        for(long id:allSlots.stream().map(DemoSlot::id).sorted().toList()) requiredLong("SELECT id FROM med_slot WHERE id=? FOR UPDATE",id);
        for(long id:allSlots.stream().map(DemoSlot::scheduleId).distinct().sorted().toList())
            requiredLong("SELECT id FROM med_schedule WHERE id=? FOR SHARE",id);
        slots=todaySlots(doctor,today);
        if(!pastSlots.isEmpty()) pastSlots=todaySlots(doctor,pastSlots.getFirst().day());
        // Completed history and today's queue use independent registry stages.
        // An early startup can seed past results without permanently suppressing
        // the queue that an administrator refreshes once check-in becomes legal.
        if(needCompleted) {
            List<DemoSlot> candidates=historicalCompletion?pastSlots:slots;
            Map<Integer,DemoSlot> allocation=allocateFixtures(doctor,patients,List.of(2,3),candidates,now);
            if(allocation.size()==2) {
                for(int kind:List.of(2,3)) createClinicalFixture(owner,doctorUser,doctor,patients[kind],allocation.get(kind),kind,drugs,now,counts);
                register(completedKey,today,"CLINICAL_COMPLETED",null,historicalCompletion
                        ?"完成示例置于过去正常门诊，时间不落未来":"两个顺序接诊的已完成示例");
            } else counts.warnings.add("未找到两个可用的既往接诊时段，本次未补完成示例，后续刷新可重试。");
        }
        if(needQueue) {
            Map<Integer,DemoSlot> allocation=allocateFixtures(doctor,patients,List.of(0,1,4),slots,now);
            if(allocation.size()==3) {
                for(int kind:List.of(0,1,4)) createClinicalFixture(owner,doctorUser,doctor,patients[kind],allocation.get(kind),kind,drugs,now,counts);
                register(queueKey,today,"CLINICAL_QUEUE",null,"今日候诊2/过号1；签到与操作时间不晚于当前数据库时间");
            } else {
                counts.warnings.add("今日候诊/过号示例尚无足够的合法签到时段；未生成未来签到，开诊前30分钟起可刷新补齐。");
            }
        }
        if(registered(completedKey) && registered(queueKey))
            register(key,today,"CLINICAL_DAY",null,"分阶段准备完成；后续刷新保留用户操作状态");
        return counts.encounters>before;
    }

    private Map<Integer,DemoSlot> allocateFixtures(long doctor,long[] patients,List<Integer> kinds,
            List<DemoSlot> candidates,LocalDateTime now) {
        Map<Integer,DemoSlot> allocation=new LinkedHashMap<>();Map<Long,Integer> reserved=new HashMap<>();
        for(int kind:kinds) {
            for(DemoSlot slot:candidates) {
                Long remaining=oneLong("SELECT remaining_capacity FROM med_slot WHERE id=?",slot.id());
                boolean completed=kind==2 || kind==3;
                boolean validTime=completed?pastWindowAvailable(doctor,slot,kind,now)
                        :demoCheckInTime(slot.day().atTime(slot.start()),slot.day().atTime(slot.end()),now)!=null;
                if(remaining!=null && remaining>reserved.getOrDefault(slot.id(),0) && validTime
                        && oneLong("SELECT id FROM med_appointment WHERE patient_id=? AND slot_id=? AND active_flag=1",patients[kind],slot.id())==null) {
                    allocation.put(kind,slot);reserved.merge(slot.id(),1,Integer::sum);break;
                }
            }
        }
        return allocation;
    }

    /** Pure timing policy for fixtures; production check-in rules are unchanged. */
    static LocalDateTime demoCheckInTime(LocalDateTime start,LocalDateTime end,LocalDateTime now) {
        if(!now.toLocalDate().equals(start.toLocalDate())) return null;
        LocalDateTime dayStart=start.toLocalDate().atStartOfDay();
        LocalDateTime earliest=start.minusMinutes(30).isBefore(dayStart)?dayStart:start.minusMinutes(30);
        if(now.isBefore(earliest)) return null;
        LocalDateTime preferred=start.minusMinutes(10).isBefore(dayStart)?dayStart:start.minusMinutes(10);
        LocalDateTime chosen=now.isBefore(preferred)?now:preferred;
        return chosen.isBefore(end)?chosen:null;
    }

    private void createMorningFixture(long doctor,long room,long morning,LocalDate date,Counts counts) {
        long schedule=insert("""
                INSERT INTO med_schedule(doctor_id,clinic_room_id,shift_template_id,work_date,start_time,end_time,
                    slot_duration_minutes,capacity_per_slot,status)
                VALUES(?,?,?,?,'08:00:00','12:00:00',30,10,'PUBLISHED')
                """,doctor,room,morning,date);
        for(LocalTime start=LocalTime.of(8,0);start.isBefore(LocalTime.of(12,0));start=start.plusMinutes(30)) {
            insert("""
                    INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity,status)
                    VALUES(?,?,?,?,?,10,10,'OPEN')
                    """,schedule,date,start,start.plusMinutes(30),date.atTime(start));
            counts.slots++;
        }
        counts.schedules++;
        register("schedule:"+doctor+":"+date+":08:00",date,"SCHEDULE",schedule,"仅演示夹具：普通上午门诊");
    }

    private List<DemoSlot> pastClinicalSlots(long doctor,long room,long morning,LocalDate today,LocalDateTime now,Counts counts) {
        for(int offset=1;offset<=7;offset++) {
            LocalDate date=today.minusDays(offset);
            List<DemoSlot> existing=todaySlots(doctor,date);
            if(hasTwoPastWindows(doctor,existing,now)) return existing;
            if(overlap(doctor,room,date,LocalTime.of(8,0),LocalTime.of(12,0))==null) {
                createMorningFixture(doctor,room,morning,date,counts);
                return todaySlots(doctor,date);
            }
        }
        return List.of();
    }

    private boolean hasTwoPastWindows(long doctor,List<DemoSlot> slots,LocalDateTime now) {
        Map<Long,Integer> reserved=new HashMap<>();
        for(int kind:List.of(2,3)) {
            DemoSlot chosen=null;
            for(DemoSlot slot:slots) {
                if(slot.remaining()>reserved.getOrDefault(slot.id(),0) && pastWindowAvailable(doctor,slot,kind,now)) {
                    chosen=slot;break;
                }
            }
            if(chosen==null) return false;
            reserved.merge(chosen.id(),1,Integer::sum);
        }
        return true;
    }

    private boolean pastWindowAvailable(long doctor,DemoSlot slot,int kind,LocalDateTime now) {
        LocalDateTime start=slot.day().atTime(slot.start()).plusMinutes(kind==3?15:2);
        LocalDateTime end=slot.day().atTime(slot.start()).plusMinutes(kind==3?25:12);
        if(end.isAfter(now)) return false;
        return oneLong("""
                SELECT id FROM med_encounter WHERE doctor_id=? AND status IN ('IN_PROGRESS','COMPLETED')
                  AND started_at<? AND (completed_at>? OR status='IN_PROGRESS') LIMIT 1
                """,doctor,end,start)==null;
    }

    private List<DemoSlot> todaySlots(long doctor,LocalDate day) {
        return jdbc.query("""
                SELECT sl.id,sl.schedule_id,sl.work_date,sl.start_time,sl.end_time,sl.remaining_capacity
                FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id
                WHERE s.doctor_id=? AND sl.work_date=? AND s.status='PUBLISHED' AND sl.status='OPEN'
                ORDER BY sl.start_time,sl.id
                """,(rs,row)->new DemoSlot(rs.getLong("id"),rs.getLong("schedule_id"),rs.getObject("work_date",LocalDate.class),
                rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class),rs.getInt("remaining_capacity")),doctor,day);
    }

    private void createClinicalFixture(long owner,long doctorUser,long doctor,long patient,DemoSlot slot,int kind,
            List<Long> drugs,LocalDateTime now,Counts counts) {
        LocalDateTime start=slot.day().atTime(slot.start());
        boolean complete=kind==2 || kind==3;
        LocalDateTime checkedIn=complete?start.minusMinutes(10)
                :demoCheckInTime(start,slot.day().atTime(slot.end()),now);
        if(checkedIn==null) throw new IllegalStateException("No legal past demo check-in time is available");
        // Normal templates never cross midnight; still bound to the appointment day.
        if(checkedIn.toLocalDate().isBefore(slot.day())) checkedIn=start;
        LocalDateTime created=start.minusMinutes(60),confirmed=start.minusMinutes(55);
        if(jdbc.update("UPDATE med_slot SET remaining_capacity=remaining_capacity-1 WHERE id=? AND remaining_capacity>0",slot.id())!=1)
            throw new IllegalStateException("Demo slot capacity changed while locked");
        long appointment=insert("""
                INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline,created_at,updated_at)
                VALUES(?,?,'BOOKED',?,?,?,?)
                """,patient,slot.id(),created.plusMinutes(15),start,created,confirmed);
        insert("INSERT INTO med_appointment_status_log(appointment_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,NULL,'PENDING_CONFIRMATION','CREATED',?,?)",appointment,owner,created);
        insert("INSERT INTO med_appointment_status_log(appointment_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,'PENDING_CONFIRMATION','BOOKED','CONFIRMED',?,?)",appointment,owner,confirmed);
        String status=complete?"COMPLETED":kind==4?"SKIPPED":"WAITING";
        LocalDateTime began=complete?start.plusMinutes(kind==3?15:2):null;
        LocalDateTime finished=complete?start.plusMinutes(kind==3?25:12):null;
        LocalDateTime skippedAt=start.plusMinutes(5).isAfter(now)?now:start.plusMinutes(5);
        long encounter=insert("""
                INSERT INTO med_encounter(appointment_id,doctor_id,status,checked_in_at,started_at,completed_at,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,appointment,doctor,status,checkedIn,began,finished,complete?2:kind==4?1:0,checkedIn,complete?finished:kind==4?skippedAt:checkedIn);
        logEncounter(encounter,null,"WAITING","CHECKED_IN",owner,checkedIn);
        if(complete) {
            logEncounter(encounter,"WAITING","IN_PROGRESS","STARTED",doctorUser,began);
            logEncounter(encounter,"IN_PROGRESS","COMPLETED","COMPLETED",doctorUser,finished);
            insert("""
                    INSERT INTO med_medical_record(encounter_id,chief_complaint,present_illness,diagnosis,advice,version,created_at,updated_at)
                    VALUES(?,?,?,?,?,1,?,?)
                    """,encounter,"【演示虚构】模拟不适，无真实患者信息。","【演示虚构】本记录用于展示门诊接诊和病历查看流程。",
                    "【演示虚构】模拟诊断，不用于真实医疗。","【演示虚构】用药与医嘱仅用于软件演示。",began,finished);
            seedPrescription(encounter,owner,drugs,kind==3,kind==3?start.plusMinutes(13):start,counts);
        } else if(kind==4) logEncounter(encounter,"WAITING","SKIPPED","SKIPPED",doctorUser,skippedAt);
        counts.appointments++;counts.encounters++;
    }

    private void seedPrescription(long encounter,long owner,List<Long> drugIds,boolean paid,LocalDateTime start,Counts counts) {
        List<DrugSnapshot> drugs=new ArrayList<>();
        for(long id:drugIds.stream().sorted().toList()) {
            List<DrugSnapshot> enabled=jdbc.query("SELECT id,code,name,specification,unit,unit_price FROM med_drug WHERE id=? AND status='ENABLED' FOR UPDATE",
                    (rs,row)->new DrugSnapshot(rs.getLong("id"),rs.getString("code"),rs.getString("name"),rs.getString("specification"),rs.getString("unit"),rs.getBigDecimal("unit_price")),id);
            if(!enabled.isEmpty()) drugs.add(enabled.getFirst());
            if(drugs.size()==2) break;
        }
        if(drugs.isEmpty()) {
            counts.warnings.add("演示药品均已停用，本次跳过处方与收费示例，保留管理员设置。");
            return;
        }
        BigDecimal total=drugs.getFirst().price().multiply(BigDecimal.valueOf(2));
        if(drugs.size()==2) total=total.add(drugs.get(1).price());
        LocalDateTime issued=start.plusMinutes(8);
        long rx=insert("INSERT INTO med_prescription(encounter_id,status,total_amount,version,created_at,issued_at,updated_at) VALUES(?,'ISSUED',?,2,?,?,?)",
                encounter,total,start.plusMinutes(3),issued,issued);
        for(int index=0;index<drugs.size();index++) {
            DrugSnapshot drug=drugs.get(index);int quantity=index==0?2:1;
            insert("""
                    INSERT INTO med_prescription_item(prescription_id,drug_id,drug_code,drug_name,specification,unit,quantity,instructions,unit_price,line_amount)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,rx,drug.id(),drug.code(),drug.name(),drug.specification(),drug.unit(),quantity,"【演示虚构】仅用于模拟处方与收费流程",drug.price(),drug.price().multiply(BigDecimal.valueOf(quantity)));
        }
        LocalDateTime paidAt=paid?start.plusMinutes(10):null;
        long bill=insert("INSERT INTO med_bill(prescription_id,status,total_amount,paid_at,created_at,updated_at) VALUES(?,?,?,?,?,?)",rx,paid?"PAID":"UNPAID",total,paidAt,issued,paid?paidAt:issued);
        if(paid) {
            insert("INSERT INTO med_mock_payment(bill_id,paid_by_user_id,amount,paid_at) VALUES(?,?,?,?)",bill,owner,total,paidAt);
            counts.payments++;
        }
        counts.prescriptions++;
    }

    private void logEncounter(long encounter,String from,String to,String event,long actor,LocalDateTime at) {
        insert("INSERT INTO med_encounter_status_log(encounter_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,?,?,?,?,?)",encounter,from,to,event,actor,at);
    }
    private boolean registered(String key) { return oneLong("SELECT 1 FROM med_demo_seed_registry WHERE seed_key=?",key)!=null; }
    private Long registryEntity(String key) { return oneLong("SELECT entity_id FROM med_demo_seed_registry WHERE seed_key=?",key); }
    private void register(String key,LocalDate day,String type,Long id,String note) {
        jdbc.update("INSERT INTO med_demo_seed_registry(seed_key,seed_date,entity_type,entity_id,note) VALUES(?,?,?,?,?)",key,day,type,id,note);
    }
    private Long oneLong(String sql,Object... args) {
        List<Long> values=jdbc.query(sql,(rs,row)->rs.getObject(1,Long.class),args);
        return values.isEmpty()?null:values.getFirst();
    }
    private long requiredLong(String sql,Object... args) {
        Long value=oneLong(sql,args);if(value==null) throw new IllegalStateException("Required demo resource was not found");return value;
    }
    private long insert(String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int index=0;index<args.length;index++) statement.setObject(index+1,args[index]);return statement;},key);
        return key.getKey().longValue();
    }
    private record DoctorIdentity(long id,long departmentId) {}
    private record CatalogueDoctor(int index,long doctorId,long userId,long roomId) {}
    private record ProfileFields(String name,String title,String specialty,String introduction) {}
    private record DemoDrug(String code,String name,String specification,String unit,BigDecimal price) {}
    private record DrugSnapshot(long id,String code,String name,String specification,String unit,BigDecimal price) {}
    private record DemoSlot(long id,long scheduleId,LocalDate day,LocalTime start,LocalTime end,int remaining) {}
    private static final class Counts {
        int schedules,slots,patients,drugs,appointments,encounters,prescriptions,payments,skipped;
        final Set<String> warnings=new LinkedHashSet<>();
    }
}
