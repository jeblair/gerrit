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

package com.google.gerrit.client.reviewdb;

import com.google.gwtorm.client.Column;
import com.google.gwtorm.client.IntKey;

import java.sql.Timestamp;

/** A single revision of a {@link Change}. */
public final class PatchSet {
  public static final PatchSet.Id BASE = new PatchSet.Id(new Change.Id(-1), -1);

  public static class Id extends IntKey<Change.Id> {
    @Column
    protected Change.Id changeId;

    @Column
    protected int patchSetId;

    protected Id() {
      changeId = new Change.Id();
    }

    public Id(final Change.Id change, final int id) {
      this.changeId = change;
      this.patchSetId = id;
    }

    @Override
    public Change.Id getParentKey() {
      return changeId;
    }

    @Override
    public int get() {
      return patchSetId;
    }

    @Override
    protected void set(int newValue) {
      patchSetId = newValue;
    }

    /** Parse a PatchSet.Id out of a string representation. */
    public static Id parse(final String str) {
      final Id r = new Id();
      r.fromString(str);
      return r;
    }
  }

  @Column(name = Column.NONE)
  protected Id id;

  @Column(notNull = false)
  protected RevId revision;

  @Column(name = "uploader_account_id")
  protected Account.Id uploader;

  /** When this patch set was first introduced onto the change. */
  @Column
  protected Timestamp createdOn;

  protected PatchSet() {
  }

  public PatchSet(final PatchSet.Id k) {
    id = k;
  }

  public PatchSet.Id getId() {
    return id;
  }

  public int getPatchSetId() {
    return id.get();
  }

  public RevId getRevision() {
    return revision;
  }

  public void setRevision(final RevId i) {
    revision = i;
  }

  public Account.Id getUploader() {
    return uploader;
  }

  public void setUploader(final Account.Id who) {
    uploader = who;
  }

  public Timestamp getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(final Timestamp ts) {
    createdOn = ts;
  }

  public String getRefName() {
    final StringBuilder r = new StringBuilder();
    r.append("refs/changes/");
    final int changeId = id.getParentKey().get();
    final int m = changeId % 100;
    if (m < 10) {
      r.append('0');
    }
    r.append(m);
    r.append('/');
    r.append(changeId);
    r.append('/');
    r.append(id.get());
    return r.toString();
  }
}
