package process.pipeline.tasks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MIG-418: how many calls of the per-row steps one workspace may have in flight on this Core, all its steps and runs together
 * ({@code process.pipeline.calls-at-once.workspace}, 8 by default). A step's own "Calls at once" is at most 8 as well.
 */
@Component
public class CallsAtOnceSetting {

    public CallsAtOnceSetting(@Value("${process.pipeline.calls-at-once.workspace:8}") int workspace) {
        AtOnce.workspaceCap(workspace);
    }
}
