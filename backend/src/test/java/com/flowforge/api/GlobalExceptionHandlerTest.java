package com.flowforge.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.api.error.GlobalExceptionHandler;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.config.AppConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GlobalExceptionHandlerTest.ThrowingController.class)
@Import({AppConfig.class, GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ThrowingController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void validationErrorsProduceProblemDetailsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("nothing here"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @Test
    void unsupportedMethodIsProblemDetailsToo() throws Exception {
        mockMvc.perform(post("/test/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @RestController
    static class ThrowingController {

        record Payload(@NotBlank String name) {
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }

        @GetMapping("/test/missing")
        void missing() {
            throw new ResourceNotFoundException("nothing here");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internals");
        }
    }
}
