import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Commit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectWriter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import java.util.Date;
import java.util.TimeZone;
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    final Date when = author.getWhen();
    final TimeZone tz = author.getTimeZone();

    author = new PersonIdent("J. Author", "ja@example.com");
    author = new PersonIdent(author, when, tz);

    committer = new PersonIdent("J. Committer", "jc@example.com");
    committer = new PersonIdent(committer, when, tz);
  }
