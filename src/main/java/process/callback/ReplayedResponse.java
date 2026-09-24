package process.callback;

import process.model.dto.ResponseDto;

/**
 * The answer a callback was first given, handed back to a redelivery of it (MIG-18).
 *
 * The worker sees exactly what it would have seen the first time; the type is only for the controller,
 * which must not repeat the first delivery's own after-effects, such as spending the run's token.
 *
 * @author Nabeel Ahmed
 */
public class ReplayedResponse extends ResponseDto {

    public ReplayedResponse(CallbackReceipts.Receipt receipt, Object data) {
        super(receipt.outcomeStatus, receipt.outcomeMessage, data);
    }
}
