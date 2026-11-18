package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class PublicPythonSourceReaderTest {
    @Test
    void bundledWorkerStartsAndRejectsHttpBeforeAnyNetworkRequest() {
        var reader = new PublicPythonSourceReader(new ObjectMapper(), System.getenv("MAINTENANCE_PYTHON"));
        var error = assertThrows(ResponseStatusException.class,
                () -> reader.read(URI.create("http://example.com/owned.py"), 1024));
        assertEquals("AT_PUBLIC_SOURCE_URL_INVALID", error.getReason());
    }
}
