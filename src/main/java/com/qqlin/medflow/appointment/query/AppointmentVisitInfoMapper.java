package com.qqlin.medflow.appointment.query;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AppointmentVisitInfoMapper {
    @Select("""
        SELECT a.id AS appointment_id,a.patient_id,COALESCE(a.booking_patient_name,p.name) AS patient_name,
          COALESCE(a.booking_doctor_name,d.name) AS doctor_name,
          COALESCE(a.booking_department_name,dept.name) AS department_name,
          COALESCE(a.booking_clinic_room_name,r.name) AS clinic_room_name,s.work_date,sl.start_time,sl.end_time,
          a.status,a.confirm_deadline,a.cancel_deadline,e.id AS encounter_id,e.status AS encounter_status,
          (a.status='BOOKED' AND e.id IS NULL AND s.work_date=CURRENT_DATE
            AND CURRENT_TIMESTAMP(3)>=TIMESTAMP(s.work_date,sl.start_time)-INTERVAL 30 MINUTE
            AND CURRENT_TIMESTAMP(3)<TIMESTAMP(s.work_date,sl.end_time)) AS can_check_in,
          CASE WHEN a.booking_snapshot_at IS NOT NULL THEN a.booking_doctor_title ELSE d.title END AS doctor_title,
          CASE WHEN source_a.id IS NOT NULL THEN source_e.id ELSE NULL END AS source_encounter_id,
          source_a.id AS source_appointment_id,source_sl.work_date AS source_work_date,
          (a.booking_snapshot_at IS NOT NULL) AS has_booking_snapshot
        FROM med_appointment a JOIN med_patient p ON p.id=a.patient_id
        JOIN med_slot sl ON sl.id=a.slot_id JOIN med_schedule s ON s.id=sl.schedule_id
        JOIN med_doctor d ON d.id=s.doctor_id JOIN med_department dept ON dept.id=d.department_id
        JOIN med_clinic_room r ON r.id=s.clinic_room_id
        LEFT JOIN med_encounter e ON e.appointment_id=a.id
        LEFT JOIN med_encounter source_e ON source_e.id=a.source_encounter_id AND source_e.status='COMPLETED'
        LEFT JOIN med_appointment source_a ON source_a.id=source_e.appointment_id AND source_a.patient_id=a.patient_id
        LEFT JOIN med_slot source_sl ON source_sl.id=source_a.slot_id
        WHERE a.id=#{id} AND p.owner_user_id=#{userId}
        """)
    AppointmentVisitInfo findOwned(@Param("id") long id, @Param("userId") long userId);

    @Select("""
        <script>
        SELECT a.id AS appointment_id,a.patient_id,COALESCE(a.booking_patient_name,p.name) AS patient_name,
          COALESCE(a.booking_doctor_name,d.name) AS doctor_name,
          COALESCE(a.booking_department_name,dept.name) AS department_name,
          COALESCE(a.booking_clinic_room_name,r.name) AS clinic_room_name,s.work_date,sl.start_time,sl.end_time,
          a.status,a.confirm_deadline,a.cancel_deadline,e.id AS encounter_id,e.status AS encounter_status,
          (a.status='BOOKED' AND e.id IS NULL AND s.work_date=CURRENT_DATE
            AND CURRENT_TIMESTAMP(3)&gt;=TIMESTAMP(s.work_date,sl.start_time)-INTERVAL 30 MINUTE
            AND CURRENT_TIMESTAMP(3)&lt;TIMESTAMP(s.work_date,sl.end_time)) AS can_check_in,
          CASE WHEN a.booking_snapshot_at IS NOT NULL THEN a.booking_doctor_title ELSE d.title END AS doctor_title,
          CASE WHEN source_a.id IS NOT NULL THEN source_e.id ELSE NULL END AS source_encounter_id,
          source_a.id AS source_appointment_id,source_sl.work_date AS source_work_date,
          (a.booking_snapshot_at IS NOT NULL) AS has_booking_snapshot
        FROM med_appointment a JOIN med_patient p ON p.id=a.patient_id
        JOIN med_slot sl ON sl.id=a.slot_id JOIN med_schedule s ON s.id=sl.schedule_id
        JOIN med_doctor d ON d.id=s.doctor_id JOIN med_department dept ON dept.id=d.department_id
        JOIN med_clinic_room r ON r.id=s.clinic_room_id
        LEFT JOIN med_encounter e ON e.appointment_id=a.id
        LEFT JOIN med_encounter source_e ON source_e.id=a.source_encounter_id AND source_e.status='COMPLETED'
        LEFT JOIN med_appointment source_a ON source_a.id=source_e.appointment_id AND source_a.patient_id=a.patient_id
        LEFT JOIN med_slot source_sl ON source_sl.id=source_a.slot_id
        WHERE p.owner_user_id=#{userId} AND a.id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY a.id
        </script>
        """)
    List<AppointmentVisitInfo> findOwnedBatch(@Param("ids") List<Long> ids, @Param("userId") long userId);
}
