package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.config.ModelSettings;
import cn.har01d.atmaintenance.dto.UpdateModelConnectionRequest;
import cn.har01d.atmaintenance.entity.ModelBudget;
import cn.har01d.atmaintenance.repository.ModelBudgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionSettingsServiceTest {
    @Test
    void activeCallRejectsConfigurationSwitchBeforeReadingOrWritingSettings() {
        var repository = mock(ModelBudgetRepository.class);
        var budget = new ModelBudget();
        budget.setActiveCallId("active-call");
        when(repository.lock()).thenReturn(Optional.of(budget));
        var settings = mock(ConnectionSettingsStore.class);
        var client = mock(ModelClient.class);
        var service = new ConnectionSettingsService(settings, new ModelSettings(), mock(AtReadClient.class),
                client, repository, new ObjectMapper(), mock(Validator.class));
        var input = new UpdateModelConnectionRequest(false, "", "", "", "", false, "",
                8000, 2048, "max_tokens", "none", false);
        var error = assertThrows(ResponseStatusException.class, () -> service.updateModel(input));
        assertEquals("MODEL_BUSY", error.getReason());
        verifyNoInteractions(settings, client);
    }
}
