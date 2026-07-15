package nlfilterlab;

import java.util.ArrayList;
import java.util.List;

final class SimulationResult {
    final String original;
    String rendered;
    final List<Trace> traces = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();

    SimulationResult(String original) {
        this.original = original;
        this.rendered = original;
    }

    record Trace(String identifier, String section, int replacements, String note) {
    }
}
