package eu.gaiax.difs.fc.core.service.sdstore.impl;

import eu.gaiax.difs.fc.core.exception.ServerException;
import eu.gaiax.difs.fc.core.pojo.ContentAccessor;
import eu.gaiax.difs.fc.core.pojo.PaginatedResults;
import eu.gaiax.difs.fc.core.service.graphdb.GraphStore;
import eu.gaiax.difs.fc.core.service.filestore.FileStore;
import eu.gaiax.difs.fc.api.generated.model.SelfDescriptionStatus;
import eu.gaiax.difs.fc.core.exception.ConflictException;
import eu.gaiax.difs.fc.core.exception.NotFoundException;
import eu.gaiax.difs.fc.core.pojo.SdFilter;
import eu.gaiax.difs.fc.core.pojo.SelfDescriptionMetadata;
import eu.gaiax.difs.fc.core.pojo.Validator;
import eu.gaiax.difs.fc.core.pojo.VerificationResult;
import eu.gaiax.difs.fc.core.service.sdstore.SelfDescriptionStore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EntityExistsException;
import javax.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.FileExistsException;
import org.apache.commons.lang3.mutable.MutableInt;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * File system based implementation of the self-description store interface.
 *
 * @author hylke
 * @author j_reuter
 */
@Slf4j
@Component
@Transactional
public class SelfDescriptionStoreImpl implements SelfDescriptionStore {

  private static final String ORDER_BY_HQL = "order by sd.statusTime desc, sd.sdHash";

  /**
   * The fileStore to use.
   */
  @Autowired
  @Qualifier("sdFileStore")
  private FileStore fileStore;

  @Autowired
  private SessionFactory sessionFactory;

  @Autowired
  private GraphStore graphDb;

  @Override
  public ContentAccessor getSDFileByHash(final String hash) {
    try {
      return fileStore.readFile(hash);
    } catch (final FileNotFoundException exc) {
      log.error("Error in getSDFileByHash method: ", exc);
      return null;
    } catch (final IOException exc) {
      log.error("failed reading self-description file with hash {}", hash);
      // TODO: Need correct error handling if we get something other than FileNotFoundException
      throw new ServerException("Failed reading self-description file with hash " + hash, exc);
    }
  }

  private void checkNonNull(final SdMetaRecord sdmRecord, final String hash) {
    if (sdmRecord == null) {
      final String message = String.format("no self-description found for hash %s", hash);
      throw new NotFoundException(message);
    }
  }

  @Override
  public SelfDescriptionMetadata getByHash(final String hash) {
    final SdMetaRecord sdmRecord = sessionFactory.getCurrentSession().byId(SdMetaRecord.class).load(hash);
    checkNonNull(sdmRecord, hash);
    // FIXME: Inconsistent exception handling: IOException will be caught and null
    //  returned; but NotFoundException will be propagated to caller.
    final ContentAccessor sdFile = getSDFileByHash(hash);
    if (sdFile == null) {
      throw new ServerException("Self-Description with hash " + hash + " not found in the file storage.");
    }
    sdmRecord.setSelfDescription(sdFile);
    return sdmRecord;
  }

  private static class FilterQueryBuilder {

    private final SessionFactory sessionFactory;
    private final List<Clause> clauses;
    private int firstResult;
    private int maxResults;

    private static class Clause {

      private static final char PLACEHOLDER_SYMBOL = '?';
      private final String formalParameterName;
      private final Object actualParameter;
      private final String templateInstance;

      private Clause(final String template, final String formalParameterName, final Object actualParameter) {
        if (actualParameter == null) {
          throw new IllegalArgumentException("value for parameter " + formalParameterName + " is null");
        }
        this.formalParameterName = formalParameterName;
        this.actualParameter = actualParameter;
        final int placeholderPosition = template.indexOf(PLACEHOLDER_SYMBOL);
        if (placeholderPosition < 0) {
          throw new IllegalArgumentException("missing parameter placeholder '" + PLACEHOLDER_SYMBOL + "' in template: " + template);
        }
        templateInstance = template.substring(0, placeholderPosition) + ":" + formalParameterName + template.substring(placeholderPosition + 1);
        if (templateInstance.indexOf(PLACEHOLDER_SYMBOL, placeholderPosition + 1) != -1) {
          throw new IllegalArgumentException("multiple parameter placeholders '" + PLACEHOLDER_SYMBOL + "' in template: " + template);
        }
      }
    }

    private FilterQueryBuilder(final SessionFactory sessionFactory) {
      this.sessionFactory = sessionFactory;
      clauses = new ArrayList<>();
    }

    private void addClause(final String template, final String formalParameterName,
        final Object actualParameter) {
      clauses.add(new Clause(template, formalParameterName, actualParameter));
    }

    private void setFirstResult(final int firstResult) {
      this.firstResult = firstResult;
    }

    private void setMaxResults(final int maxResults) {
      this.maxResults = maxResults;
    }

    private String buildHqlQuery() {
      final StringBuilder hqlWhere = new StringBuilder();
      clauses.stream().forEach(clause -> {
        if (hqlWhere.length() > 0) {
          hqlWhere.append(" and");
        } else {
          hqlWhere.append(" where");
        }
        hqlWhere.append(" (");
        hqlWhere.append(clause.templateInstance);
        hqlWhere.append(")");
      });
      final String hqlQuery = "select sd from SdMetaRecord sd" + hqlWhere + " " + ORDER_BY_HQL;
      log.debug("hqlQuery=" + hqlQuery);
      return hqlQuery;
    }

    private Query<SdMetaRecord> createQuery() {
      final String hqlQuery = buildHqlQuery();
      final Session currentSession = sessionFactory.getCurrentSession();
      final Query<SdMetaRecord> query = currentSession.createQuery(hqlQuery, SdMetaRecord.class);
      clauses.stream().forEach(clause -> query.setParameter(clause.formalParameterName, clause.actualParameter));
      query.setFirstResult(firstResult);
      if (maxResults != 0) {
        /*
         * As specified, we model maxResults as int rather than as Integer, such that it
         * is not nullable. However, since "query.setMaxResults(0)" will make Hibernate
         * to return an empty result set rather than not imposing any limit, the only
         * way to keep the option not to apply any limit is to reserve a special value
         * of maxResults. We choose the value "0" for this purpose (since this is the
         * default initialization value for int variables in Java). That is, we call
         * "query.setMaxResults(maxResults)" only for values other than 0. For
         * maxResults < 0, Hibernate itself will already throw a runtime exception, such
         * that we do not need to handle negative values here.
         */
        query.setMaxResults(maxResults);
      }
      return query;
    }
  }

  @Override
  public PaginatedResults<SelfDescriptionMetadata> getByFilter(final SdFilter filter) {
    final FilterQueryBuilder queryBuilder = new FilterQueryBuilder(sessionFactory);

    final Instant uploadTimeStart = filter.getUploadTimeStart();
    if (uploadTimeStart != null) {
      queryBuilder.addClause("uploadtime >= ?", "uploadTimeStart", uploadTimeStart);
      final Instant uploadTimeEnd = filter.getUploadTimeEnd();
      queryBuilder.addClause("uploadtime <= ?", "uploadTimeEnd", uploadTimeEnd);
    }

    final Instant statusTimeStart = filter.getStatusTimeStart();
    if (statusTimeStart != null) {
      queryBuilder.addClause("statustime >= ?", "statusTimeStart", statusTimeStart);
      final Instant statusTimeEnd = filter.getStatusTimeEnd();
      queryBuilder.addClause("statustime <= ?", "statusTimeEnd", statusTimeEnd);
    }

    final String issuer = filter.getIssuer();
    if (issuer != null) {
      queryBuilder.addClause("issuer = ?", "issuer", issuer);
    }

    final String validator = filter.getValidator();
    if (validator != null) {
      queryBuilder.addClause("? = inarray(validators)", "validator", validator);
    }

    final SelfDescriptionStatus status = filter.getStatus();
    if (status != null) {
      queryBuilder.addClause("status = ?", "status", status);
    }

    final String subjectId = filter.getId();
    if (subjectId != null) {
      queryBuilder.addClause("subjectid = ?", "subjectId", subjectId);
    }

    final String hash = filter.getHash();
    if (hash != null) {
      queryBuilder.addClause("sdhash = ?", "hash", hash);
    }

    Long totalCount = queryBuilder.createQuery().getResultStream().distinct().count();

    queryBuilder.setFirstResult(filter.getOffset());
    queryBuilder.setMaxResults(filter.getLimit());

    return new PaginatedResults<>(totalCount, queryBuilder.createQuery().stream().collect(Collectors.toList()));
  }

  @Override
  public void storeSelfDescription(final SelfDescriptionMetadata sdMetadata,
      final VerificationResult verificationResult) {
    if (verificationResult == null) {
      throw new IllegalArgumentException("verification result must not be null");
    }
    final Session currentSession = sessionFactory.getCurrentSession();

    final SdMetaRecord existingSd = currentSession
        .createQuery("select sd from SdMetaRecord sd where sd.subjectId=?1 and sd.status=?2", SdMetaRecord.class)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setTimeout(1)
        .setParameter(1, sdMetadata.getId())
        .setParameter(2, SelfDescriptionStatus.ACTIVE)
        .uniqueResult();

    final SdMetaRecord sdmRecord = new SdMetaRecord(sdMetadata);

    final List<Validator> validators = verificationResult.getValidators();
    final boolean registerValidators = sdMetadata.getValidatorDids() == null || sdMetadata.getValidatorDids().isEmpty();
    if (validators != null) {
      Instant expDateFirst = null;
      for (Validator validator : validators) {
        Instant expDate = validator.getExpirationDate();
        if (expDateFirst == null || expDate.isBefore(expDateFirst)) {
          expDateFirst = expDate;
        }
        if (registerValidators) {
          sdmRecord.addValidatorDidsItem(validator.getDidURI());
        }
      }
      sdmRecord.setExpirationTime(expDateFirst);
    }

    if (existingSd != null) {
      existingSd.setStatus(SelfDescriptionStatus.DEPRECATED);
      existingSd.setStatusTime(Instant.now());
      currentSession.update(existingSd);
      currentSession.flush();
      graphDb.deleteClaims(existingSd.getSubjectId());
    }
    try {
      currentSession.persist(sdmRecord);
    } catch (final EntityExistsException exc) {
      log.error("storeSelfDescription.error 1", exc);
      final String message = String.format("self-description file with hash %s already exists", sdMetadata.getSdHash());
      throw new ConflictException(message);
    } catch (Exception ex) {
      log.error("storeSelfDescription.error 2", ex);
      throw ex;
    }

    graphDb.addClaims(verificationResult.getClaims(), sdmRecord.getSubjectId());

    try {
      fileStore.storeFile(sdMetadata.getSdHash(), sdMetadata.getSelfDescription());
      currentSession.flush();
    } catch (FileExistsException e) {
      throw new ConflictException("The SD file with the hash " + sdMetadata.getSdHash() + " already exists in the file storage.", e);
    } catch (final IOException exc) {
      throw new ServerException("Error while adding SD to file storage: " + exc.getMessage());
    } catch (Exception ex) {
      log.error("storeSelfDescription.error 3", ex);
      throw ex;
    }

  }

  @Override
  public void changeLifeCycleStatus(final String hash, final SelfDescriptionStatus targetStatus) {
    final Session currentSession = sessionFactory.getCurrentSession();
    // Get a lock on the record.
    // TODO: Investigate lock-less update.
    final SdMetaRecord sdmRecord = currentSession.find(SdMetaRecord.class, hash, LockModeType.PESSIMISTIC_WRITE);
    checkNonNull(sdmRecord, hash);
    final SelfDescriptionStatus status = sdmRecord.getStatus();
    if (status != SelfDescriptionStatus.ACTIVE) {
      final String message = String.format(
          "can not change status of self-description with hash %s: require status %s, but encountered status %s", hash,
          SelfDescriptionStatus.ACTIVE, status);
      throw new ConflictException(message);
    }
    sdmRecord.setStatus(targetStatus);
    sdmRecord.setStatusTime(Instant.now());
    currentSession.update(sdmRecord);

    graphDb.deleteClaims(sdmRecord.getSubjectId());
    currentSession.flush();
  }

  @Override
  public void deleteSelfDescription(final String hash) {
    final Session currentSession = sessionFactory.getCurrentSession();
    // Get a lock on the record.
    final SdMetaRecord sdmRecord = currentSession.find(SdMetaRecord.class, hash);
    checkNonNull(sdmRecord, hash);
    final SelfDescriptionStatus status = sdmRecord.getStatus();
    currentSession.delete(sdmRecord);
    currentSession.flush();
    try {
      fileStore.deleteFile(hash);
    } catch (final FileNotFoundException exc) {
      log.info("no self-description file with hash {} found for deletion", hash);
    } catch (final IOException exc) {
      log.error("failed to delete self-description file with hash {}", hash, exc);
    }

    if (status == SelfDescriptionStatus.ACTIVE) {
      graphDb.deleteClaims(sdmRecord.getSubjectId());
    }
  }

  @Override
  public int invalidateSelfDescriptions() {
    final Session currentSession = sessionFactory.getCurrentSession();
    // A possible Performance optimisation may be required here to limit the number
    // of SDs that are expired in one run, to limit the size of the Transaction.
    Stream<SdMetaRecord> existingSds = currentSession
        .createQuery("select sd from SdMetaRecord sd where sd.expirationTime < ?1 and sd.status = ?2", SdMetaRecord.class)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setTimeout(1)
        .setParameter(1, Instant.now())
        .setParameter(2, SelfDescriptionStatus.ACTIVE)
        .getResultStream();
    final MutableInt count = new MutableInt();
    existingSds.forEach((sd) -> {
      try {
        count.increment();
        changeLifeCycleStatus(sd.getSdHash(), SelfDescriptionStatus.EOL);
      } catch (ConflictException exc) {
        log.info("SD was set non-active before we could expire it. Hash: {}", sd.getSdHash());
      }
    });
    return count.intValue();
  }
}
