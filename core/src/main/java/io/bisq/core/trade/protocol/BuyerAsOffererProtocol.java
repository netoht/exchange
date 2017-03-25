/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.trade.protocol;

import io.bisq.common.handlers.ErrorMessageHandler;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.core.trade.BuyerAsOffererTrade;
import io.bisq.core.trade.Trade;
import io.bisq.core.trade.protocol.tasks.buyer.*;
import io.bisq.core.trade.protocol.tasks.offerer.*;
import io.bisq.core.util.Validator;
import io.bisq.protobuffer.message.Message;
import io.bisq.protobuffer.message.p2p.MailboxMessage;
import io.bisq.protobuffer.message.trade.DepositTxPublishedMessage;
import io.bisq.protobuffer.message.trade.PayDepositRequest;
import io.bisq.protobuffer.message.trade.PayoutTxPublishedMessage;
import io.bisq.protobuffer.message.trade.TradeMessage;
import io.bisq.protobuffer.payload.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class BuyerAsOffererProtocol extends TradeProtocol implements BuyerProtocol, OffererProtocol {
    private final BuyerAsOffererTrade buyerAsOffererTrade;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsOffererProtocol(BuyerAsOffererTrade trade) {
        super(trade);

        this.buyerAsOffererTrade = trade;

        Trade.State tradeState = trade.getState();
        Trade.Phase phase = tradeState.getPhase();
        //TODO test case
        if (trade.getPayoutTx() != null &&
                (phase == Trade.Phase.FIAT_RECEIVED || phase == Trade.Phase.PAYOUT_PAID) &&
                tradeState != Trade.State.PAYOUT_BROAD_CASTED) {
            TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                    () -> {
                        handleTaskRunnerSuccess("BuyerAsOffererMightBroadcastPayoutTx");
                        processModel.onComplete();
                    },
                    this::handleTaskRunnerFault);

            taskRunner.addTasks(BuyerAsOffererMightBroadcastPayoutTx.class);
            taskRunner.run();
        } else if (trade.getPayoutTx() == null && tradeState == Trade.State.BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG
                && phase != Trade.Phase.PAYOUT_PAID) {
            //TODO test case
            TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                    () -> {
                        handleTaskRunnerSuccess("SetupListenerForPayoutTx");
                        processModel.onComplete();
                    },
                    this::handleTaskRunnerFault);

            taskRunner.addTasks(SetupListenerForPayoutTx.class);
            taskRunner.run();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mailbox
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void doApplyMailboxMessage(Message message, Trade trade) {
        this.trade = trade;

        if (message instanceof MailboxMessage) {
            MailboxMessage mailboxMessage = (MailboxMessage) message;
            NodeAddress peerNodeAddress = mailboxMessage.getSenderNodeAddress();
            if (message instanceof DepositTxPublishedMessage)
                handle((DepositTxPublishedMessage) message, peerNodeAddress);
            else if (message instanceof PayoutTxPublishedMessage)
                handle((PayoutTxPublishedMessage) message, peerNodeAddress);
            else
                log.error("We received an unhandled MailboxMessage" + message.toString());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Start trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleTakeOfferRequest(TradeMessage message, NodeAddress peerNodeAddress) {
        Validator.checkTradeId(processModel.getId(), message);
        checkArgument(message instanceof PayDepositRequest);
        processModel.setTradeMessage(message);
        processModel.setTempTradingPeerNodeAddress(peerNodeAddress);

        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsOffererTrade,
                () -> handleTaskRunnerSuccess("handleTakeOfferRequest"),
                this::handleTaskRunnerFault);
        taskRunner.addTasks(
                ProcessPayDepositRequest.class,
                VerifyArbitrationSelection.class,
                VerifyTakerFeePayment.class,
                VerifyTakerAccount.class,
                LoadTakeOfferFeeTx.class,
                CreateAndSignContract.class,
                OffererAsBuyerCreatesAndSignsDepositTx.class,
                SetupDepositBalanceListener.class,
                SendPublishDepositTxRequest.class
        );
        startTimeout();
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(DepositTxPublishedMessage tradeMessage, NodeAddress peerNodeAddress) {
        stopTimeout();
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(peerNodeAddress);

        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsOffererTrade,
                () -> handleTaskRunnerSuccess("handle DepositTxPublishedMessage"),
                this::handleTaskRunnerFault);
        taskRunner.addTasks(ProcessDepositTxPublishedMessage.class,
                VerifyTakerFeePayment.class,
                VerifyTakerAccount.class,
                PublishTradeStatistics.class);
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    // User clicked the "bank transfer started" button
    @Override
    public void onFiatPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (buyerAsOffererTrade.getState().ordinal() <= Trade.State.BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG.ordinal()) {
            if (buyerAsOffererTrade.getState() == Trade.State.BUYER_SENT_FIAT_PAYMENT_INITIATED_MSG)
                log.warn("onFiatPaymentStarted called twice. " +
                        "That is expected if the app starts up and the other peer has still not continued.");

            buyerAsOffererTrade.setState(Trade.State.BUYER_CONFIRMED_FIAT_PAYMENT_INITIATED);

            TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsOffererTrade,
                    () -> {
                        resultHandler.handleResult();
                        handleTaskRunnerSuccess("onFiatPaymentStarted");
                    },
                    (errorMessage) -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(errorMessage);
                    });
            taskRunner.addTasks(
                    VerifyTakerFeePayment.class,
                    VerifyTakerAccount.class,
                    BuyerAsOffererSignPayoutTx.class,
                    SendFiatTransferStartedMessage.class,
                    SetupListenerForPayoutTx.class
            );
            taskRunner.run();
        } else {
            log.warn("onFiatPaymentStarted called twice. " +
                    "That should not happen.\n" +
                    "state=" + buyerAsOffererTrade.getState());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(PayoutTxPublishedMessage tradeMessage, NodeAddress peerNodeAddress) {
        log.debug("handle RequestFinalizePayoutTxMessage called");
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(peerNodeAddress);

        TradeTaskRunner taskRunner = new TradeTaskRunner(buyerAsOffererTrade,
                () -> {
                    handleTaskRunnerSuccess("handle FinalizePayoutTxRequest");
                    processModel.onComplete();
                },
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                BuyerProcessPayoutTxPublishedMessage.class,
                BuyerAsOffererMightBroadcastPayoutTx.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void doHandleDecryptedMessage(TradeMessage tradeMessage, NodeAddress peerNodeAddress) {
        if (tradeMessage instanceof DepositTxPublishedMessage) {
            handle((DepositTxPublishedMessage) tradeMessage, peerNodeAddress);
        } else if (tradeMessage instanceof PayoutTxPublishedMessage) {
            handle((PayoutTxPublishedMessage) tradeMessage, peerNodeAddress);
        } else //noinspection StatementWithEmptyBody
            if (tradeMessage instanceof PayDepositRequest) {
                // do nothing as we get called the handleTakeOfferRequest method from outside
            } else {
                log.error("Incoming decrypted tradeMessage not supported. " + tradeMessage);
            }
    }
}