package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reviewer channel: the same transition and correction as the author channel, but verifications (into or out
 * of VERIFIED, MET, NOT_MET) are recorded only here, and the history carries the reviewer login.
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
