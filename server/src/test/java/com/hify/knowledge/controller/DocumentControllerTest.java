package com.hify.knowledge.controller;

import com.hify.common.page.PageResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private DocumentService documentService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private DocumentResponse sampleDoc() {
        return new DocumentResponse(20L, 10L, "faq.txt", "txt", 1024L, "ready", 3,
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"));
    }

    @Test
    void 上传_multipart成功_返回文档资源() throws Exception {
        when(documentService.upload(eq(10L), any(), any())).thenReturn(sampleDoc());
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .file(new MockMultipartFile("file", "faq.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("20"))          // Long→string
                .andExpect(jsonPath("$.data.fileSize").value("1024"))  // Long→string
                .andExpect(jsonPath("$.data.chunkCount").value(3));    // Integer 保持数字
    }

    @Test
    void 上传_缺file字段_400且10001() throws Exception {
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 上传_未登录_401() throws Exception {
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 文档列表_返回PageResult() throws Exception {
        when(documentService.pageDocuments(eq(10L), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sampleDoc()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/datasets/10/documents?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].name").value("faq.txt"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void 删除文档_成功_data为null() throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge/documents/20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(documentService).deleteDocument(eq(20L), any());
    }

    @Test
    void 分段列表_返回position与content() throws Exception {
        when(documentService.pageChunks(eq(20L), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(new ChunkResponse(30L, 1, "第一段")), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/documents/20/chunks?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].position").value(1))
                .andExpect(jsonPath("$.data.list[0].content").value("第一段"));
    }
}
