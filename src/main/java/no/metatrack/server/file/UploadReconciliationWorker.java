package no.metatrack.server.file;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class UploadReconciliationWorker {
    private static final Logger LOG = Logger.getLogger(UploadReconciliationWorker.class);

    @Inject
    ObjectStorage objectStorage;

    @Transactional
    List<Long> pendingFileIds(long afterId, int batchSize) {
        PanacheQuery<File> query = File.find("status = ?1 and id > ?2 order by id", UploadStatus.PENDING, afterId);
        return query.page(Page.ofSize(batchSize)).list().stream().map(file -> file.id).toList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean reconcile(long fileId) {
        File file = File.findById(fileId);
        if (file == null || file.status != UploadStatus.PENDING) return false;

        return reconcile(file);
    }

    boolean reconcile(File file) {
        try {
            if (!objectStorage.objectExists(file.objectKey)) return false;
            file.status = UploadStatus.UPLOADED;
            return true;
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not reconcile pending upload (key=%s): %s", file.objectKey, e.getMessage());
            return false;
        }
    }
}