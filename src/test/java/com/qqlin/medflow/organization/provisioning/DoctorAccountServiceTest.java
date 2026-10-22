package com.qqlin.medflow.organization.provisioning;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.organization.domain.*;
import com.qqlin.medflow.organization.repository.OrganizationRepository;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorAccountServiceTest {
    @Mock DoctorAccountMapper mapper;
    @Mock UserRepository users;
    @Mock OrganizationRepository organization;
    @Mock PasswordEncoder passwords;
    DoctorAccountService service;
    @BeforeEach void setup() { service = new DoctorAccountService(mapper, users, organization, passwords); }
    private void enabledDepartment() {
        when(organization.findDepartmentById(3)).thenReturn(Optional.of(new Department(3,"全科",ResourceStatus.ENABLED)));
    }
    @Test void createsAccountThenProfileWithHashedPassword() {
        enabledDepartment();
        when(passwords.encode("Doctor@123")).thenReturn("encoded-only");
        when(users.findByUsername("doctor.li")).thenReturn(Optional.of(
                new UserAccount(12,"doctor.li","encoded-only",UserRole.DOCTOR,UserStatus.ENABLED,0)));
        when(organization.insertDoctor(12,3,"李医生")).thenReturn(18L);
        Doctor doctor = service.create(" doctor.li ","Doctor@123"," 李医生 ",3);
        assertEquals(18,doctor.id());
        var order = inOrder(mapper,users,organization);
        order.verify(organization).findDepartmentById(3);
        order.verify(mapper).insert("doctor.li","encoded-only");
        order.verify(users).findByUsername("doctor.li");
        order.verify(organization).insertDoctor(12,3,"李医生");
    }
    @Test void duplicateUsernameExitsTransactionInsteadOfCreatingProfile() {
        enabledDepartment();
        when(passwords.encode(anyString())).thenReturn("hash");
        when(mapper.insert(anyString(),anyString())).thenThrow(new DuplicateKeyException("duplicate"));
        var error = assertThrows(BusinessException.class, () -> service.create("doctor.li","Doctor@123","李医生",3));
        assertEquals(ErrorCode.USERNAME_ALREADY_EXISTS,error.getErrorCode());
        verifyNoInteractions(users);
        verify(organization,never()).insertDoctor(anyLong(),anyLong(),anyString());
    }
    @Test void unicodePasswordChecksBcryptBytesBeforeAnyWrite() {
        var error = assertThrows(BusinessException.class, () -> service.create("doctor.li","医".repeat(25),"李医生",3));
        assertEquals(ErrorCode.VALIDATION_FAILED,error.getErrorCode());
        verifyNoInteractions(mapper,users,organization,passwords);
    }
    @Test void unavailableDepartmentCannotCreateAnOrphanAccount() {
        when(organization.findDepartmentById(3)).thenReturn(Optional.of(new Department(3,"全科",ResourceStatus.DISABLED)));
        var error = assertThrows(BusinessException.class, () -> service.create("doctor.li","Doctor@123","李医生",3));
        assertEquals(ErrorCode.DEPARTMENT_DISABLED,error.getErrorCode());
        verifyNoInteractions(mapper,users,passwords);
    }
}
