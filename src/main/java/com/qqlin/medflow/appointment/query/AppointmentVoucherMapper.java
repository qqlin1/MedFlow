package com.qqlin.medflow.appointment.query;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AppointmentVoucherMapper {
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    LocalDateTime databaseNow();

    @Select("""
        SELECT pr.id AS prescription_id,b.id AS bill_id,b.status,b.total_amount,b.paid_at
        FROM med_appointment a
        JOIN med_patient p ON p.id=a.patient_id
        JOIN med_encounter e ON e.appointment_id=a.id
        JOIN med_prescription pr ON pr.encounter_id=e.id AND pr.status='ISSUED'
        JOIN med_bill b ON b.prescription_id=pr.id
        WHERE a.id=#{appointmentId} AND p.owner_user_id=#{userId}
        """)
    AppointmentVoucherBill findVisibleBill(@Param("appointmentId") long appointmentId,
                                          @Param("userId") long userId);
}
