package com.fooyre.intelligence.api;

import com.fooyre.intelligence.application.AiClient.Turn;
import com.fooyre.intelligence.application.AssistantService;
import com.fooyre.intelligence.application.AssistantService.Answer;
import com.fooyre.intelligence.application.AssistantService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assistante IA ({@code AI_USE} : OWNER, MANAGER). Le texte saisi ou dicté est transmis ; jamais
 * d'audio. Historique limité (6 derniers tours, texte seul), non conservé par le serveur.
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    public record HistoryTurn(@NotNull String role, @NotNull @Size(max = 4000) String text) {
    }

    public record AskRequest(@NotBlank @Size(max = 1000) String question, @Size(max = 12) List<@Valid HistoryTurn> history) {
    }

    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('AI_USE')")
    public Status status() {
        return assistant.status();
    }

    @PostMapping("/messages")
    @PreAuthorize("hasAuthority('AI_USE')")
    public Answer ask(@Valid @RequestBody AskRequest request) {
        List<Turn> history = request.history() == null ? List.of()
                : request.history().stream().map(turn -> new Turn("user".equals(turn.role()), turn.text())).toList();
        return assistant.ask(request.question().strip(), history);
    }
}
