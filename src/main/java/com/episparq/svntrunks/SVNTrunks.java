package com.episparq.svntrunks;

import org.jgrapht.DirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNAuthenticationManager;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

import javax.swing.*;
import java.awt.*;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * @author Ian Atha <thatha@thatha.org>
 */
public class SVNTrunks {
    private final boolean headless = GraphicsEnvironment.isHeadless();
    private ProgressMonitor monitor = null;
    private final String base;
    private final SVNRepository repository;
    private final DirectedGraph<String, DefaultEdge> graph = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
    private final PriorityBlockingQueue<String> queue = new PriorityBlockingQueue<String>();

    volatile int enqueued = 0;
    volatile int solved = 0;

    private void enqueue(String s) {
        enqueued++;
        queue.add(s);
    }

    private void findTrunks() throws SVNException {
        String location;
        while (((location = queue.poll()) != null) && (headless || !monitor.isCanceled())) {
            solved++;

            if (!headless) {
                monitor.setMaximum(enqueued);
                monitor.setProgress(solved);
                monitor.setNote(String.format("(%2.0f%%) %s", (float) solved / enqueued * 100, location));
            }

            Collection<SVNDirEntry> entries = repository.getDir(location, -1, null, SVNDirEntry.DIRENT_KIND, (Collection) null);

            int structuralDirsFound = 0;
            for (SVNDirEntry entry : entries) {
                if (entry.getName().equals("trunk") || entry.getName().equals("tags") || entry.getName().equals("branches")) {
                    structuralDirsFound++;
                }
            }

            if (structuralDirsFound == 3) {
                String previous = "/";
                graph.addVertex(previous);
                for (String segment : ((location + "/trunk").split("/"))) {
                    if (segment.length() > 0) {
                        String current = previous + segment + "/";
                        graph.addVertex(current);
                        graph.addEdge(previous, current);
                        previous = current;
                    }
                }
            } else {
                int traversed = 0;
                for (SVNDirEntry entry : entries) {
                    if (entry.getKind() == SVNNodeKind.DIR) {
                        String nextLocation = location + "/" + entry.getRelativePath();
                        traversed++;
                        enqueue(nextLocation);
                    }
                }
                if (traversed == 0) {
                    // out.println("# " + location + " ends without a full [trunk, tags, branches]!");
                }
            }
        }
    }

    public SVNTrunks(String repositoryURL, PrintStream out) throws SVNException {
        if (!headless) {
            monitor = new ProgressMonitor(null, String.format("%s", repositoryURL), "", 1, 1);
        }

        if (!headless) {
            monitor.setMillisToDecideToPopup(0);
            monitor.setMillisToPopup(0);
        }

        base = repositoryURL;

        try {
            SVNRepositoryFactoryImpl.setup();
            repository = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(base));
            ISVNAuthenticationManager authManager = new DefaultSVNAuthenticationManager(
                    null, true, null, "", null, "");
            repository.setAuthenticationManager(authManager);

            enqueue("");
            findTrunks();

            if (headless || !monitor.isCanceled()) {
                out.println(String.format("svn co --depth=immediates %s && cd `basename !$`", base));
                for (Iterator<String> iterator = new TopologicalOrderIterator<String, DefaultEdge>(graph); iterator.hasNext();) {
                    String entry = iterator.next();
                    if (!entry.equals("/")) {
                        String depth;
                        if (entry.endsWith("/trunk/")) {
                            depth = "infinity";
                        } else {
                            depth = "immediates";
                        }
                        out.println(String.format("svn up --set-depth %s %s", depth, entry.replaceFirst(base, "")));
                    }
                }
                out.println(String.format("svn up && svn st && cd .."));
            } else {
                out.println("# Aborted");
            }
            repository.closeSession();
        } catch (SVNException e) {
            JOptionPane.showMessageDialog(null, e.getLocalizedMessage());
            throw e;
        } finally {
            if (!headless) {
                monitor.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("This utility expects one argument, a URI to an SVN repository.");
        }
        new SVNTrunks(args[0], System.out);
    }
}
