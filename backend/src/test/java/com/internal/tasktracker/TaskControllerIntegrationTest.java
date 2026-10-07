package com.internal.tasktracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsStableAscendingPagesAndFilteredCount() throws Exception {
        mockMvc.perform(get("/api/tasks").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(47))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[9].id").value(10));

        mockMvc.perform(get("/api/tasks").param("page", "2").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(11))
                .andExpect(jsonPath("$.items[9].id").value(22));
    }

    @Test
    void filtersByExactStatusValuesAndSearchesCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "IN_PROGRESS").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].status", everyItem(is("IN_PROGRESS"))));

        mockMvc.perform(get("/api/tasks").param("status", "DONE").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("DONE"))));

        mockMvc.perform(get("/api/tasks").param("q", "add api").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(19));

        mockMvc.perform(get("/api/tasks").param("q", "POOL EXHAUSTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(7));
    }

    @Test
    void combinesSearchAndStatusAndPaginatesTheFilteredResults() throws Exception {
        mockMvc.perform(get("/api/tasks").param("q", "api").param("status", "IN_PROGRESS")
                        .param("page", "1").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(2));

        mockMvc.perform(get("/api/tasks").param("q", "api").param("status", "IN_PROGRESS")
                        .param("page", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(16));

        mockMvc.perform(get("/api/tasks").param("status", "OPEN")
                        .param("page", "2").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThan(10)));
    }

    @Test
    void trimsBlankSearchAndReturnsAnEmptyPageForNoMatches() throws Exception {
        mockMvc.perform(get("/api/tasks").param("q", "   ").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(47))
                .andExpect(jsonPath("$.items[0].id").value(1));

        mockMvc.perform(get("/api/tasks").param("q", "no-such-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(get("/api/tasks").param("status", "   ").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(47));

        mockMvc.perform(get("/api/tasks").param("status", "UNKNOWN_STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}