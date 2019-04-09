package com.iota.iri.network.pipeline;

import com.iota.iri.TransactionValidator;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.network.neighbor.Neighbor;
import com.iota.iri.service.snapshot.SnapshotProvider;
import com.iota.iri.storage.Tangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class ReceivedStage {

    private static final Logger log = LoggerFactory.getLogger(ReceivedStage.class);

    private Tangle tangle;
    private TransactionValidator txValidator;
    private SnapshotProvider snapshotProvider;

    public ReceivedStage(Tangle tangle, TransactionValidator txValidator, SnapshotProvider snapshotProvider) {
        this.txValidator = txValidator;
        this.tangle = tangle;
        this.snapshotProvider = snapshotProvider;
    }

    public ProcessingContext process(ProcessingContext ctx) {
        ReceivedPayload payload = (ReceivedPayload) ctx.getPayload();
        Optional<Neighbor> optNeighbor = payload.getNeighbor();
        TransactionViewModel tvm = payload.getTransactionViewModel();

        boolean stored;
        try {
            stored = tvm.store(tangle, snapshotProvider.getInitialSnapshot());
        } catch (Exception e) {
            log.error("error persisting newly received tx", e);
            optNeighbor.ifPresent(neighbor -> neighbor.getMetrics().incrInvalidTransactionsCount());
            ctx.setNextStage(TxPipeline.Stage.ABORT);
            return ctx;
        }

        if (stored) {
            tvm.setArrivalTime(System.currentTimeMillis());
            try {
                txValidator.updateStatus(tvm);
                // neighbor might be null because tx came from a broadcastTransaction command
                if (optNeighbor.isPresent()) {
                    tvm.updateSender(optNeighbor.get().getHostAddressAndPort());
                }
                tvm.update(tangle, snapshotProvider.getInitialSnapshot(), "arrivalTime|sender");
            } catch (Exception e) {
                log.error("error updating newly received tx", e);
            }
            optNeighbor.ifPresent(neighbor -> neighbor.getMetrics().incrNewTransactionsCount());
        }

        // broadcast the newly saved tx to the other neighbors
        ctx.setNextStage(TxPipeline.Stage.BROADCAST);
        ctx.setPayload(new BroadcastPayload(optNeighbor.orElse(null), tvm));
        return ctx;
    }
}