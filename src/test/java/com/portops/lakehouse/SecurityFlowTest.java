package com.portops.lakehouse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色约束：接入/调度/结算账号各司其职；未认证拒绝；Swagger 公开。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityFlowTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/dispatch/containers/CNU0001")).andExpect(status().isUnauthorized());
    }

    @Test
    void dispatcherCannotFreezeManifest() throws Exception {
        mvc.perform(post("/api/manifests/freeze")
                        .with(httpBasic("dispatcher", "disp123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voyageNo\":\"V1\",\"cutoffTime\":\"2026-11-20T18:00:00+08:00\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void settlementCannotIngestEvents() throws Exception {
        mvc.perform(post("/api/ingest/events")
                        .with(httpBasic("settlement", "settle123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ingestCanPostEvents() throws Exception {
        mvc.perform(post("/api/ingest/events")
                        .with(httpBasic("ingest", "ingest123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void settlementCannotResolveConflict() throws Exception {
        mvc.perform(post("/api/commits/conflicts/1/resolve")
                        .with(httpBasic("settlement", "settle123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerEventId\":1,\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
