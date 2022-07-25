package eu.gaiax.difs.fc.server.service;

import static eu.gaiax.difs.fc.server.util.SelfDescriptionParser.getParticipantIdFromSd;
import static eu.gaiax.difs.fc.server.util.SessionUtils.getSessionParticipantId;

import eu.gaiax.difs.fc.api.generated.model.SelfDescription;
import eu.gaiax.difs.fc.api.generated.model.SelfDescription.StatusEnum;
import eu.gaiax.difs.fc.server.exception.ClientException;
import eu.gaiax.difs.fc.server.exception.ParserException;
import eu.gaiax.difs.fc.server.generated.controller.SelfDescriptionsApiDelegate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Implementation of the {@link eu.gaiax.difs.fc.server.generated.controller.SelfDescriptionsApiDelegate} interface.
 */
@Slf4j
@Service
public class SelfDescriptionService implements SelfDescriptionsApiDelegate {
  //TODO: 13.07.2022 Need to replace mocked Data with business logic

  /**
   * Service method for GET /self-descriptions : Get the list of metadata of SD in the Catalogue.
   *
   * @param uploadTimeRange Filter for the time range when the SD was uploaded to the catalogue.
   *                        The time range has to be specified as start time and end time as ISO8601 timestamp
   *                        separated by a &#x60;/&#x60;. (optional)
   * @param statusTimeRange Filter for the time range when the status of the SD was last changed in the catalogue.
   *                        The time range has to be specified as start time and end time as ISO8601 timestamp
   *                        separated by a &#x60;/&#x60;. (optional)
   * @param issuer          Filter for the issuer of the SD. This is the unique ID of the Participant
   *                        that has prepared the SD. (optional)
   * @param validator       Filter for a validator of the SD. This is the unique ID of the Participant
   *                        that validated (part of) the SD. (optional)
   * @param status          Filter for the status of the SD. (optional, default to active)
   * @param id              Filter for id/credentialSubject of the SD. (optional)
   * @param hash            Filter for a hash of the SD. (optional)
   * @param offset          The number of items to skip before starting to collect the result set.
   *                        (optional, default to 0)
   * @param limit           The number of items to return. (optional, default to 100)
   * @return List of meta-data of available SD. (status code 200)
   *        or May contain hints how to solve the error or indicate what was wrong in the request. (status code 400)
   *        or May contain hints how to solve the error or indicate what went wrong at the server.
   *        Must not outline any information about the internal structure of the server. (status code 500)
   */
  @Override
  public ResponseEntity<List<SelfDescription>> readSelfDescriptions(String uploadTimeRange,
                                                                    String statusTimeRange,
                                                                    String issuer, String validator,
                                                                    String status, String id,
                                                                    String hash,
                                                                    Integer offset, Integer limit) {
    log.debug("readSelfDescriptions.enter; got uploadTimeRange: {}, statusTimeRange: {},"
            + "issuer: {}, validator: {}, status: {}, id: {}, hash: {}, offset: {}, limit: {}",
        uploadTimeRange, statusTimeRange, issuer, validator, status, id, hash, offset, limit);
    List<SelfDescription> selfDescriptions = new ArrayList<>();
    selfDescriptions.add(getDefaultSdMetadata());
    log.debug("readSelfDescriptions.exit; returning: {}", selfDescriptions.size());
    return new ResponseEntity<>(selfDescriptions, HttpStatus.OK);
  }

  /**
   * Service method for GET /self-descriptions/{self_description_hash} : Read a SD by its hash. Returns the content
   * of the single SD.
   *
   * @param selfDescriptionHash Hash of the self-description (required)
   * @return The requested Self-Description (status code 200)
   *         or May contain hints how to solve the error or indicate what was wrong in the request. (status code 400)
   *         or Self-Description not found (status code 404)
   *         or May contain hints how to solve the error or indicate what went wrong at the server.
   *         Must not outline any information about the internal structure of the server. (status code 500)
   */
  @Override
  public ResponseEntity<Object> readSelfDescriptionByHash(String selfDescriptionHash) {
    log.debug("readSelfDescriptionByHash.enter; got hash: {}", selfDescriptionHash);

    String sd = "{\n"
        + " \"@context\": \"https://json-ld.org/contexts/gaia-x.jsonld\",\n"
        + " \"@id\": \"http://dbpedia.org/resource/MyService\",\n"
        + " \"provider\": \"http://dbpedia.org/resource/MyProvider\",\n"
        + " \"name\": \"https://gaia-x.catalogue.com\"\n"
        + " }";

    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.set("Content-Type", "application/ld+json");

    log.debug("readSelfDescriptionByHash.exit; returning self-description by hash: {}", selfDescriptionHash);
    return ResponseEntity.ok()
        .headers(responseHeaders)
        .body(sd);
  }

  /**
   * Service method for DELETE /self-descriptions/{self_description_hash} : Completely delete a SD.
   *
   * @param selfDescriptionHash Hash of the SD (required)
   * @return OK (status code 200)
   *         or May contain hints how to solve the error or indicate what was wrong in the request. (status code 400)
   *         or Forbidden. The user does not have the permission to execute this request. (status code 403)
   *         or May contain hints how to solve the error or indicate what went wrong at the server.
   *         Must not outline any information about the internal structure of the server. (status code 500)
   */
  @Override
  public ResponseEntity<Void> deleteSelfDescription(String selfDescriptionHash) {
    log.debug("deleteSelfDescription.enter; got hash: {}", selfDescriptionHash);

    String selfDescription = getSelfDescriptionByHash(selfDescriptionHash);
    checkParticipantAccess(selfDescription);

    log.debug("deleteSelfDescription.exit; deleted self-description by hash: {}",
        selfDescriptionHash);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Service method for POST /self-descriptions : Add a new SD to the catalogue.
   *
   * @param selfDescription The new SD (required)
   * @return Created (status code 201)
   *         or The request was accepted but the validation is not finished yet. (status code 202)
   *         or May contain hints how to solve the error or indicate what was wrong in the request. (status code 400)
   *         or Forbidden. The user does not have the permission to execute this request. (status code 403)
   *         or May contain hints how to solve the error or indicate what went wrong at the server.
   *         Must not outline any information about the internal structure of the server. (status code 500)
   */
  @Override
  public ResponseEntity<SelfDescription> addSelfDescription(String selfDescription) {
    log.debug("addSelfDescription.enter; got selfDescription: {}", selfDescription);

    checkParticipantAccess(selfDescription);
    SelfDescription sdMetadata = getDefaultSdMetadata();

    log.debug("addSelfDescription.exit; returning self-description metadata by hash: {}", sdMetadata.getSdHash());
    return new ResponseEntity<>(sdMetadata, HttpStatus.CREATED);
  }

  /**
   * Service method for POST /self-descriptions/{self_description_hash}/revoke :
   * Change the lifecycle state of a SD to revoke.
   *
   * @param selfDescriptionHash Hash of the self-description (required)
   * @return Revoked (status code 200)
   *         or May contain hints how to solve the error or indicate what was wrong in the request. (status code 400)
   *         or Forbidden. The user does not have the permission to execute this request. (status code 403)
   *         or May contain hints how to solve the error or indicate what went wrong at the server.
   *         Must not outline any information about the internal structure of the server. (status code 500)
   */
  @Override
  public ResponseEntity<SelfDescription> updateSelfDescription(String selfDescriptionHash) {
    log.debug("updateSelfDescription.enter; got hash: {}", selfDescriptionHash);

    String selfDescription = getSelfDescriptionByHash(selfDescriptionHash);
    checkParticipantAccess(selfDescription);

    SelfDescription sdMetadata = getDefaultSdMetadata();

    log.debug("updateSelfDescription.exit; update self-description by hash: {}", selfDescriptionHash);
    return new ResponseEntity<>(sdMetadata, HttpStatus.OK);
  }

  /**
   * Internal service method for checking user access to a particular Participant.
   *
   * @param selfDescription The verifiable SD (required).
   */
  private void checkParticipantAccess(String selfDescription) {
    String sdParticipantId;
    try {
      sdParticipantId = getParticipantIdFromSd(selfDescription);
    } catch (ParserException exception) {
      log.debug(exception.getMessage(), exception);
      throw new ClientException(exception.getMessage());
    }
    String sessionParticipantId = getSessionParticipantId();
    if (Objects.isNull(sdParticipantId) || Objects.isNull(sessionParticipantId)
        || !sdParticipantId.equals(sessionParticipantId)) {
      log.debug(
          "checkParticipantAccess; The user does not have access to the specified participant."
              + " User participant id = {}, self-description participant id = {}.",
          sessionParticipantId, sdParticipantId);
      throw new AccessDeniedException("The user does not have access to the specified participant.");
    }
  }

  // TODO: 20.07.2022 The logic must be implemented by Fraunhofer.
  //  Then the mock implementation will be changed.
  private String getSelfDescriptionByHash(String sdHash) {
    return "{\n"
        + "  \"@context\": [\n"
        + "    \"https://w3id.org/gaia-x/context.jsonld\"\n"
        + "  ],\n"
        + "  \"type\": \"VerifiablePresentation\",\n"
        + "  \"verifiableCredential\": [\n"
        + "    {\n"
        + "      \"@context\": [\n"
        + "        \"https://w3id.org/gaia-x/context.jsonld\"\n"
        + "      ],\n"
        + "      \"id\": \"http://example.edu/credentials/1872\",\n"
        + "      \"type\": \"VerifiableCredential\",\n"
        + "      \"issuer\": \"https://www.handelsregister.de/\",\n"
        + "      \"issuanceDate\": \"2010-01-01T19:73:24Z\",\n"
        + "      \"credentialSubject\": {\n"
        + "        \"@id\": \"http://example.org/test-provider\",\n"
        + "        \"@type\": \"gax:Provider\",\n"
        + "        \"gax:hasLegallyBindingName\": \"My example provider\"\n"
        + "      },\n"
        + "      \"proof\": {\n"
        + "        \"type\": \"RsaSignature2018\",\n"
        + "        \"created\": \"2017-06-18T21:19:10Z\",\n"
        + "        \"proofPurpose\": \"assertionMethod\",\n"
        + "        \"verificationMethod\": \"https://example.edu/issuers/keys/1\",\n"
        + "        \"jws\": \"eyJhbGciOiJSUzI1NiIsImI2NCI6ZmFsc2UsImNyaXQiOlsiYjY0Il19\"\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"proof\": {\n"
        + "    \"type\": \"RsaSignature2018\",\n"
        + "    \"created\": \"2018-09-14T21:19:10Z\",\n"
        + "    \"proofPurpose\": \"authentication\",\n"
        + "    \"verificationMethod\": \"did:example:ebfeb1f712ebc6f1c276e12ec21#keys1\",\n"
        + "    \"challenge\": \"1f44d55f-f161-4938-a659-f8026467f126\",\n"
        + "    \"domain\": \"4jt78h47fh47\",\n"
        + "    \"jws\": \"eyJhbGciOiJSUzI1NiIsImI2NCI6ZmFsc2UsImNyaXQiOlsiYjY0Il19\"\n"
        + "  }\n"
        + "}\n";
  }

  private SelfDescription getDefaultSdMetadata() {
    SelfDescription sdMetadata = new SelfDescription();
    sdMetadata.setId("string");
    sdMetadata.setSdHash("string");
    sdMetadata.setIssuer("string");
    sdMetadata.setStatus(StatusEnum.ACTIVE);
    List<String> validators = new ArrayList<>();
    validators.add("string");
    sdMetadata.setValidators(validators);
    sdMetadata.setStatusTime("2022-05-11T15:30:00Z");
    sdMetadata.setUploadTime("2022-03-01T13:00:00Z");
    return sdMetadata;
  }
}