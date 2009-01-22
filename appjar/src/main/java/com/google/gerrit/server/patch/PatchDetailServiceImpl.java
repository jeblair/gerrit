// Copyright 2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.patch;

import com.google.gerrit.client.data.ApprovalType;
import com.google.gerrit.client.data.SideBySidePatchDetail;
import com.google.gerrit.client.data.UnifiedPatchDetail;
import com.google.gerrit.client.patches.PatchDetailService;
import com.google.gerrit.client.reviewdb.Account;
import com.google.gerrit.client.reviewdb.ApprovalCategory;
import com.google.gerrit.client.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.client.reviewdb.Change;
import com.google.gerrit.client.reviewdb.ChangeApproval;
import com.google.gerrit.client.reviewdb.ChangeMessage;
import com.google.gerrit.client.reviewdb.Patch;
import com.google.gerrit.client.reviewdb.PatchLineComment;
import com.google.gerrit.client.reviewdb.PatchSet;
import com.google.gerrit.client.reviewdb.PatchSetInfo;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.client.rpc.BaseServiceImplementation;
import com.google.gerrit.client.rpc.Common;
import com.google.gerrit.client.rpc.NoSuchEntityException;
import com.google.gerrit.git.RepositoryCache;
import com.google.gerrit.server.ChangeMail;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.GerritJsonServlet;
import com.google.gerrit.server.GerritServer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwtjsonrpc.client.VoidResult;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.OrmRunnable;
import com.google.gwtorm.client.Transaction;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.MessagingException;

public class PatchDetailServiceImpl extends BaseServiceImplementation implements
    PatchDetailService {
  private final GerritServer server;

  public PatchDetailServiceImpl(final GerritServer gs) {
    server = gs;
  }

  public void sideBySidePatchDetail(final Patch.Key key,
      final List<PatchSet.Id> versions,
      final AsyncCallback<SideBySidePatchDetail> callback) {
    final RepositoryCache rc = server.getRepositoryCache();
    if (rc == null) {
      callback.onFailure(new Exception("No Repository Cache configured"));
      return;
    }
    run(callback, new SideBySidePatchDetailAction(rc, key, versions));
  }

  public void unifiedPatchDetail(final Patch.Key key,
      final AsyncCallback<UnifiedPatchDetail> callback) {
    run(callback, new UnifiedPatchDetailAction(key));
  }

  public void myDrafts(final Patch.Key key,
      final AsyncCallback<List<PatchLineComment>> callback) {
    run(callback, new Action<List<PatchLineComment>>() {
      public List<PatchLineComment> run(ReviewDb db) throws OrmException {
        return db.patchComments().draft(key, Common.getAccountId()).toList();
      }
    });
  }

  public void saveDraft(final PatchLineComment comment,
      final AsyncCallback<PatchLineComment> callback) {
    run(callback, new Action<PatchLineComment>() {
      public PatchLineComment run(ReviewDb db) throws OrmException, Failure {
        if (comment.getStatus() != PatchLineComment.Status.DRAFT) {
          throw new Failure(new IllegalStateException("Comment published"));
        }

        final Patch patch = db.patches().get(comment.getKey().getParentKey());
        final Change change;
        if (patch == null) {
          throw new Failure(new NoSuchEntityException());
        }
        change = db.changes().get(patch.getKey().getParentKey().getParentKey());
        assertCanRead(change);

        final Account.Id me = Common.getAccountId();
        if (comment.getKey().get() == null) {
          final PatchLineComment nc =
              new PatchLineComment(new PatchLineComment.Key(patch.getKey(),
                  ChangeUtil.messageUUID(db)), comment.getLine(), me);
          nc.setSide(comment.getSide());
          nc.setMessage(comment.getMessage());
          db.patchComments().insert(Collections.singleton(nc));
          return nc;

        } else {
          if (!me.equals(comment.getAuthor())) {
            throw new Failure(new NoSuchEntityException());
          }
          comment.updated();
          db.patchComments().update(Collections.singleton(comment));
          return comment;
        }
      }
    });
  }

  public void deleteDraft(final PatchLineComment.Key commentKey,
      final AsyncCallback<VoidResult> callback) {
    run(callback, new Action<VoidResult>() {
      public VoidResult run(ReviewDb db) throws OrmException, Failure {
        final PatchLineComment comment = db.patchComments().get(commentKey);
        if (comment == null) {
          throw new Failure(new NoSuchEntityException());
        }
        if (!Common.getAccountId().equals(comment.getAuthor())) {
          throw new Failure(new NoSuchEntityException());
        }
        if (comment.getStatus() != PatchLineComment.Status.DRAFT) {
          throw new Failure(new IllegalStateException("Comment published"));
        }
        db.patchComments().delete(Collections.singleton(comment));
        return VoidResult.INSTANCE;
      }
    });
  }

  public void publishComments(final PatchSet.Id psid, final String message,
      final Set<ApprovalCategoryValue.Id> approvals,
      final AsyncCallback<VoidResult> callback) {
    run(callback, new Action<VoidResult>() {
      public VoidResult run(ReviewDb db) throws OrmException, Failure {
        final PublishResult r;

        r = db.run(new OrmRunnable<PublishResult, ReviewDb>() {
          public PublishResult run(ReviewDb db, Transaction txn, boolean retry)
              throws OrmException {
            return doPublishComments(psid, message, approvals, db, txn);
          }
        });

        try {
          final ChangeMail cm = new ChangeMail(server, r.change);
          cm.setFrom(Common.getAccountId());
          cm.setPatchSet(r.patchSet, r.info);
          cm.setChangeMessage(r.message);
          cm.setPatchLineComments(r.comments);
          cm.setReviewDb(db);
          cm.setHttpServletRequest(GerritJsonServlet.getCurrentCall()
              .getHttpServletRequest());
          cm.sendComment();
        } catch (MessagingException e) {
          throw new Failure(e);
        }
        return VoidResult.INSTANCE;
      }
    });
  }

  private static class PublishResult {
    Change change;
    PatchSet patchSet;
    PatchSetInfo info;
    ChangeMessage message;
    List<PatchLineComment> comments;
  }

  private PublishResult doPublishComments(final PatchSet.Id psid,
      final String messageText, final Set<ApprovalCategoryValue.Id> approvals,
      final ReviewDb db, final Transaction txn) throws OrmException {
    final PublishResult r = new PublishResult();
    final Account.Id me = Common.getAccountId();
    r.change = db.changes().get(psid.getParentKey());
    r.patchSet = db.patchSets().get(psid);
    r.info = db.patchSetInfo().get(psid);
    if (r.change == null || r.patchSet == null || r.info == null) {
      throw new OrmException(new NoSuchEntityException());
    }

    r.comments = db.patchComments().draft(psid, me).toList();
    final Set<Patch.Key> patchKeys = new HashSet<Patch.Key>();
    for (final PatchLineComment c : r.comments) {
      patchKeys.add(c.getKey().getParentKey());
    }
    final Map<Patch.Key, Patch> patches =
        db.patches().toMap(db.patches().get(patchKeys));
    for (final PatchLineComment c : r.comments) {
      final Patch p = patches.get(c.getKey().getParentKey());
      if (p != null) {
        p.setCommentCount(p.getCommentCount() + 1);
      }
      c.setStatus(PatchLineComment.Status.PUBLISHED);
      c.updated();
    }
    db.patches().update(patches.values(), txn);
    db.patchComments().update(r.comments, txn);

    final StringBuilder msgbuf = new StringBuilder();
    final Map<ApprovalCategory.Id, ApprovalCategoryValue.Id> values =
        new HashMap<ApprovalCategory.Id, ApprovalCategoryValue.Id>();
    for (final ApprovalCategoryValue.Id v : approvals) {
      values.put(v.getParentKey(), v);
    }

    final boolean open = r.change.getStatus().isOpen();
    final Map<ApprovalCategory.Id, ChangeApproval> have =
        new HashMap<ApprovalCategory.Id, ChangeApproval>();
    for (final ChangeApproval a : db.changeApprovals().byChangeUser(
        r.change.getId(), me)) {
      have.put(a.getCategoryId(), a);
    }
    for (final ApprovalType at : Common.getGerritConfig().getApprovalTypes()) {
      final ApprovalCategoryValue.Id v = values.get(at.getCategory().getId());
      if (v == null) {
        continue;
      }

      final ApprovalCategoryValue val = at.getValue(v.get());
      if (val == null) {
        continue;
      }

      ChangeApproval mycatpp = have.remove(v.getParentKey());
      if (mycatpp == null) {
        if (msgbuf.length() > 0) {
          msgbuf.append("; ");
        }
        msgbuf.append(val.getName());
        if (open) {
          mycatpp =
              new ChangeApproval(new ChangeApproval.Key(r.change.getId(), me, v
                  .getParentKey()), v.get());
          db.changeApprovals().insert(Collections.singleton(mycatpp), txn);
        }

      } else if (mycatpp.getValue() != v.get()) {
        if (msgbuf.length() > 0) {
          msgbuf.append("; ");
        }
        msgbuf.append(val.getName());
        if (open) {
          mycatpp.setValue(v.get());
          mycatpp.setGranted();
          db.changeApprovals().update(Collections.singleton(mycatpp), txn);
        }
      }
    }
    if (open) {
      db.changeApprovals().delete(have.values(), txn);
    }

    if (msgbuf.length() > 0) {
      msgbuf.insert(0, "Patch Set " + psid.get() + ": ");
      msgbuf.append("\n");
    }
    if (messageText != null) {
      msgbuf.append(messageText);
    }
    if (msgbuf.length() > 0) {
      r.message =
          new ChangeMessage(new ChangeMessage.Key(r.change.getId(), ChangeUtil
              .messageUUID(db)), me);
      r.message.setMessage(msgbuf.toString());
      db.changeMessages().insert(Collections.singleton(r.message), txn);
    }

    ChangeUtil.updated(r.change);
    db.changes().update(Collections.singleton(r.change), txn);
    return r;
  }
}
