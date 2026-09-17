-- Development account used to bind a real DOCTOR identity to a doctor profile.
-- The production bootstrap process will be separated before deployment.
INSERT INTO sys_user (
    username,
    password_hash,
    role,
    status,
    token_version
)
VALUES (
    'doctor.wang',
    '$2a$10$KhBQ2jz3xcKv95hfShuTY.heRCZKx00ReiIjpn3iNUb8HsL4nvjNW',
    'DOCTOR',
    'ENABLED',
    0
);
