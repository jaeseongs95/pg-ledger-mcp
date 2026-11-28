// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import org.veridger.application.write.WriteResult.CorrectResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records verification decisions and independent returns for rework. The history carries the reviewer login.
 */
@Service
@Transactional
public class ReviewService {

    private final TransitionOperation transition;
    private final CorrectOperation correct;

    public ReviewService(TransitionOperation transition, CorrectOperation correct) {
        this.transition = transition;
        this.correct = correct;
    }

    public TransitionResult review(String actor, TransitionCommand command) {
        return transition.run(actor, Channel.REVIEWER, command);
    }

    public CorrectResult reviewCorrect(String actor, CorrectCommand command) {
        return correct.run(actor, Channel.REVIEWER, command);
    }
}
