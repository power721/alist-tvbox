package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RepairPatch {
    private final ObjectMapper mapper;

    public String encode(RepairProposal proposal) {
        try {
            String value = mapper.writeValueAsString(proposal);
            if (value.getBytes(StandardCharsets.UTF_8).length > 32768) throw error("PATCH_TOO_LARGE");
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Repair proposal cannot be serialized", e);
        }
    }

    public String apply(String baseline, RepairProposal proposal) {
        var edits = new TreeMap<Integer, cn.har01d.atmaintenance.dto.RepairEdit>();
        for (var edit : proposal.edits()) {
            int at = baseline.indexOf(edit.oldText());
            if (at < 0 || baseline.indexOf(edit.oldText(), at + 1) >= 0) throw error("PATCH_MATCH_NOT_UNIQUE");
            var previous = edits.floorEntry(at);
            if (previous != null && previous.getKey() + previous.getValue().oldText().length() > at)
                throw error("PATCH_OVERLAP");
            var next = edits.ceilingEntry(at);
            if (next != null && at + edit.oldText().length() > next.getKey()) throw error("PATCH_OVERLAP");
            edits.put(at, edit);
        }
        StringBuilder result = new StringBuilder(baseline);
        for (var entry : edits.descendingMap().entrySet()) {
            result.replace(entry.getKey(), entry.getKey() + entry.getValue().oldText().length(), entry.getValue().newText());
        }
        String body = result.toString();
        if (body.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw error("CANDIDATE_TOO_LARGE");
        return body;
    }

    private ResponseStatusException error(String code) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }
}
