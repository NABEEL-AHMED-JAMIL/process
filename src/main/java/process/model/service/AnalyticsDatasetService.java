package process.model.service;

import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsDataset;

/**
 * Registering a dataset: naming a location once so it can be come back to.
 *
 * <b>Why this exists at all.</b> analytics_dataset was created by V31 and, until this service,
 * held zero rows -- AnalyticsDatasetRepository had no caller anywhere in src/main. The master
 * index's golden workflow names "dataset registration" as a step between file selection and
 * schema detection, and spec 09 asks for a list and a fetch, so the table was the shape of a
 * feature nobody had written. It is written here.
 *
 * <b>What registration is NOT.</b> It is not a precondition for reading anything. Every reading
 * endpoint in the module still takes a connection alias and a path directly, and none of them
 * consults this table; a person who never registers a dataset loses nothing but the bookmark.
 * That is deliberate -- making a registry row a precondition for opening a file would put a
 * filing step in front of the one thing the module is for.
 *
 * <b>What it does not store, for the third time in this module.</b> No bucket, no endpoint, no
 * region, no credential. A dataset row carries the connection ALIAS and the path;
 * {@code DatasetResolver} looks the alias up when the dataset is opened, so a connection later
 * repointed at a different bucket moves its saved datasets with it rather than leaving them
 * quietly reading the old one. This service resolves once, at registration, to check the caller
 * can actually reach what they are saving -- and it keeps the DatasetRef's verdict, never its
 * location.
 *
 * There is no update method, and that is not an oversight. A dataset row is four facts about a
 * location; changing any of them makes it a different dataset, and V32's changeset already
 * records that analytics_dataset "has no update path at all" -- which stays a true description of
 * the code rather than becoming a stale comment.
 *
 * @author Nabeel Ahmed
 */
public interface AnalyticsDatasetService {

    /** Every registered dataset the caller's workspace can see, newest first. */
    ResponseDto fetchAllDatasets() throws Exception;

    /**
     * One dataset, or a not-found for anything the caller does not own.
     *
     * This is the endpoint spec 11's data-leakage clause was written about -- "never allow a user
     * to reference another tenant's dataset ID by guessing an identifier". Until this service
     * existed there was no dataset id on the wire anywhere in the module and the clause had no
     * premise; now there is one, so the refusal here and the refusal for an id that does not
     * exist are deliberately the same sentence.
     */
    ResponseDto fetchDatasetById(Long analyticsDatasetId) throws Exception;

    /**
     * Registers a dataset: a name, a connection alias and a path inside it.
     *
     * The row is built here rather than bound from the request. The tenant and the audit columns
     * come from the signed-in context, so "register this into another workspace" is not a request
     * this method can be made to honour, and the format label is taken from what the resolver
     * derived from the path rather than from whatever the caller typed.
     *
     * A connection the caller cannot reach is refused with the resolver's own words, which are
     * the same words for "no such connection" and "not yours" -- so registration cannot be used
     * to find out which aliases other workspaces own.
     */
    ResponseDto registerDataset(AnalyticsDataset payload) throws Exception;

    /**
     * Deletes a registered dataset outright.
     *
     * A hard delete, matching the saved-query library: nothing resolves through a dataset row and
     * no report counts one, so a tombstone would earn nothing and would keep a name in a list
     * somebody meant to be rid of. Nothing else points at this row either -- saved queries and
     * saved analyses name their own alias and path -- so deleting one takes nothing with it.
     */
    ResponseDto deleteDataset(Long analyticsDatasetId) throws Exception;

}
