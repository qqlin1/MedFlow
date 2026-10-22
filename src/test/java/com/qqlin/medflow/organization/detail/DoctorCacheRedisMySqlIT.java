package com.qqlin.medflow.organization.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Six checks in one ordered scenario, including shutting down ONLY our owned Redis process. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "medflow.appointment.expiration.enabled=false", "medflow.demo.accounts.enabled=false",
        "medflow.doctor-cache.ttl=PT30S", "medflow.doctor-cache.null-ttl=PT10S",
        "logging.level.com.qqlin.medflow.organization.detail=DEBUG"})
class DoctorCacheRedisMySqlIT {
    static final String URL=isolatedUrl();
    static final int REDIS_PORT=Integer.parseInt(required("MEDFLOW_CACHE_TEST_REDIS_PORT"));
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DoctorDetailService service;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean DoctorDetailRepository repository;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->URL);
        registry.add("spring.datasource.username",()->"root"); registry.add("spring.datasource.password",()->"");
        registry.add("medflow.security.jwt.secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("spring.data.redis.host",()->"127.0.0.1"); registry.add("spring.data.redis.port",()->REDIS_PORT);
        registry.add("spring.data.redis.password",()->"");
    }
    @Test void executeAllSixChecksAgainstRealHttpMysqlRedisAndCli() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("medflow_confirmation_test");
        assertOwnedRedis();
        String departmentName="cache-test-"+UUID.randomUUID();
        jdbc.update("INSERT INTO med_department(name) VALUES (?)",departmentName);
        long department=jdbc.queryForObject("SELECT id FROM med_department WHERE name=?",Long.class,departmentName);
        jdbc.update("INSERT INTO med_doctor(user_id,department_id,name) SELECT id,?,'缓存演示医生' FROM sys_user WHERE username='doctor.wang'",department);
        long doctor=jdbc.queryForObject("SELECT id FROM med_doctor WHERE department_id=?",Long.class,department);
        String patient=login("patient.li","Patient@123"), admin=login("admin","Admin@123");
        String key=DoctorDetailCache.key(doctor), path="/api/v1/patient/doctors/"+doctor;
        redis.delete(key); clearInvocations(repository);

        var first=request("GET",path,patient,null,200);
        assertThat(redis.opsForValue().get(key)).contains(first.path("data").path("name").asText());
        verify(repository,times(1)).findEnabled(doctor);
        var second=request("GET",path,patient,null,200);
        assertThat(second.path("data")).isEqualTo(first.path("data")); verify(repository,times(1)).findEnabled(doctor);
        System.out.println("CHECK 1-2 PASS: first MySQL+fill; second Redis HIT; doctor detail SELECT count=1");

        long ttl=Long.parseLong(cli("TTL",key)); assertThat(ttl).isBetween(1L,30L);
        System.out.println("CHECK 3 PASS: redis-cli TTL "+key+" => "+ttl+" seconds");

        String original=first.path("data").path("name").asText();
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            service.updateName(doctor,"事务提交后更新");
            assertThat(redis.opsForValue().get(key)).contains(original); // DEL has not happened before commit.
        });
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(request("GET",path,patient,null,200).path("data").path("name").asText()).isEqualTo("事务提交后更新");
        tx.executeWithoutResult(status -> { service.updateName(doctor,"回滚的名字"); status.setRollbackOnly(); });
        assertThat(redis.opsForValue().get(key)).contains("事务提交后更新");
        assertThat(jdbc.queryForObject("SELECT name FROM med_doctor WHERE id=?",String.class,doctor)).isEqualTo("事务提交后更新");
        request("PUT","/api/v1/admin/doctors/"+doctor+"/profile",patient,"{\"name\":\"越权\"}",403);
        request("PUT","/api/v1/admin/doctors/"+doctor+"/profile",admin,"{\"name\":\"HTTP更新医生\"}",200);
        assertThat(redis.hasKey(key)).isFalse();
        request("GET",path,patient,null,200);
        request("PUT","/api/v1/admin/doctors/"+doctor+"/profile",admin,
                "{\"name\":\"HTTP更新医生\",\"title\":\"主治医师\",\"specialty\":\"虚构复诊咨询\",\"introduction\":\"虚构公开简介\"}",200);
        assertThat(redis.hasKey(key)).isFalse();
        var biography=request("GET",path,patient,null,200).path("data");
        assertThat(biography.path("title").asText()).isEqualTo("主治医师");
        assertThat(biography.path("specialty").asText()).isEqualTo("虚构复诊咨询");
        assertThat(cli("GET",key)).contains("虚构公开简介");
        tx.executeWithoutResult(status->{service.updateProfile(doctor,"HTTP更新医生","回滚职称","回滚擅长","回滚简介");status.setRollbackOnly();});
        assertThat(cli("GET",key)).contains("虚构公开简介").doesNotContain("回滚简介");
        System.out.println("CHECK 4 PASS: cache survives before commit and rollback; deleted after commit; admin-only HTTP update");

        long absent=9_000_000_001L; String nullKey=DoctorDetailCache.key(absent); redis.delete(nullKey); clearInvocations(repository);
        request("GET","/api/v1/patient/doctors/"+absent,patient,null,404);
        request("GET","/api/v1/patient/doctors/"+absent,patient,null,404);
        verify(repository,times(1)).findEnabled(absent);
        assertThat(cli("GET",nullKey)).isEqualTo(DoctorDetailCache.NULL_VALUE);
        assertThat(Long.parseLong(cli("TTL",nullKey))).isBetween(1L,10L);
        System.out.println("CHECK 5 PASS: absent doctor 404 twice, SELECT count=1, redis-cli GET => __NULL__, TTL <=10s");

        // TTL expiration is real, with bounded polling instead of relying on a timing-only sleep.
        cli("PEXPIRE",key,"150"); long expiryDeadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime()<expiryDeadline) Thread.sleep(20);
        assertThat(redis.hasKey(key)).isFalse(); clearInvocations(repository); request("GET",path,patient,null,200);
        verify(repository,times(1)).findEnabled(doctor);

        assertOwnedRedis(); ProcessHandle owned=ProcessHandle.of(Long.parseLong(required("MEDFLOW_CACHE_TEST_REDIS_PID"))).orElseThrow();
        assertThat(owned.destroy()).isTrue(); owned.onExit().get(); clearInvocations(repository);
        long begin=System.nanoTime(); request("GET",path,patient,null,200);
        long firstMs=Duration.ofNanos(System.nanoTime()-begin).toMillis();
        begin=System.nanoTime(); request("GET",path,patient,null,200);
        long nextMs=Duration.ofNanos(System.nanoTime()-begin).toMillis();
        verify(repository,times(2)).findEnabled(doctor);
        assertThat(firstMs).isLessThan(2500); assertThat(nextMs).isLessThan(2500);
        request("PUT","/api/v1/admin/doctors/"+doctor+"/profile",admin,"{\"name\":\"缓存停机仍可更新\"}",200);
        assertThat(request("GET",path,patient,null,200).path("data").path("name").asText()).isEqualTo("缓存停机仍可更新");
        System.out.println("CHECK 6 PASS: owned Redis STOPPED; HTTP 200 via MySQL; first="+firstMs+"ms next="+nextMs+"ms; committed update succeeds despite DEL failure");
    }
    String login(String username,String password) throws Exception {
        return request("POST","/api/v1/auth/login","",json.writeValueAsString(Map.of("username",username,"password",password)),200)
                .path("data").path("accessToken").asText();
    }
    JsonNode request(String method,String path,String token,String body,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(4));
        if (!token.isBlank()) builder.header("Authorization","Bearer "+token);
        if (body!=null) builder.header("Content-Type","application/json");
        var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method+" "+path+" "+response.body()).isEqualTo(expected); return json.readTree(response.body());
    }
    String cli(String... arguments) throws Exception {
        var command=new ArrayList<String>(List.of(required("MEDFLOW_CACHE_TEST_REDIS_CLI"),"-h","127.0.0.1","-p",String.valueOf(REDIS_PORT),"--raw"));
        command.addAll(List.of(arguments)); var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim(); assertThat(process.waitFor()).isZero(); return result;
    }
    void assertOwnedRedis() {
        long pid=Long.parseLong(required("MEDFLOW_CACHE_TEST_REDIS_PID"));
        String config=required("MEDFLOW_CACHE_TEST_REDIS_CONFIG");
        assertThat(config.replace('\\','/')).contains("/target/doctor-cache-").endsWith("/redis.conf");
        var process=ProcessHandle.of(pid).orElseThrow();
        assertThat(process.isAlive()).isTrue();
        // Windows may hide commandLine even from a child JVM; the harness also marks its private instance.
        process.info().commandLine().ifPresent(command -> assertThat(command).containsIgnoringCase("memurai"));
        assertThat(redis.opsForValue().get("medflow:test:owner")).isEqualTo(required("MEDFLOW_CACHE_TEST_OWNER"));
    }
    static String required(String name) { return Optional.ofNullable(System.getenv(name)).filter(s->!s.isBlank()).orElseThrow(()->new IllegalStateException("Run tools/test-doctor-cache.ps1; missing "+name)); }
    static String isolatedUrl() {
        String url=required("MEDFLOW_CONFIRMATION_TEST_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/medflow_confirmation_test(?:\\?.*)?")) throw new IllegalStateException("Disposable schema required");
        return url;
    }
}
