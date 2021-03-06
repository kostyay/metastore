package io.anemos.metastore.core.registry;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import io.anemos.metastore.config.GitGlobalConfig;
import io.anemos.metastore.config.GitHostConfig;
import io.anemos.metastore.config.RegistryConfig;
import io.anemos.metastore.putils.ProtoDomain;
import io.anemos.metastore.v1alpha1.RegistryP.SubmitSchemaRequest.Comment;
import io.opencensus.common.Scope;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MetaGit {
  private static final Logger LOG = LoggerFactory.getLogger(MetaGit.class);
  private static final Tracer TRACER = Tracing.getTracer();
  private final RegistryConfig config;
  private final GitGlobalConfig global;
  private Git gitRepo;
  private TransportConfigCallback transportConfigCallback;

  MetaGit(RegistryConfig config, GitGlobalConfig global) {
    this.config = config;
    this.global = global;
  }

  private void push() throws GitAPIException {
    gitRepo.push().setTransportConfigCallback(transportConfigCallback).call();
  }

  private void pull() throws GitAPIException {
    gitRepo.pull().setTransportConfigCallback(transportConfigCallback).call();
  }

  private void clean(ProtoDomain domain) throws GitAPIException {
    clean(domain, new File(config.git.path));
  }

  private void clean(ProtoDomain domain, File dir) throws GitAPIException {
    File repo = new File(config.git.path);
    File[] files = dir.listFiles();
    for (File file : files) {
      if (file.isDirectory()) {
        clean(domain, file);
      } else if (file.isFile() && file.getName().toLowerCase().endsWith(".proto")) {
        String filePattern = file.getAbsolutePath().replace(repo.getAbsolutePath(), "");
        if (filePattern.startsWith("/")) {
          filePattern = filePattern.substring(1);
        }
        if (domain.getFileDescriptorByFileName(filePattern) == null) {
          gitRepo.rm().addFilepattern(filePattern).call();
        }
      }
    }
  }

  void sync(ProtoDomain protoContainer, Comment comment) {
    if (config.git == null) {
      return;
    }

    try (Scope ss = TRACER.spanBuilder("GitSync").setRecordEvents(true).startScopedSpan()) {
      if (System.getenv("DEBUG") != null && System.getenv("DEBUG").equals("true")) {
        protoContainer.writeToDirectory(new File(config.git.path).toPath().toString());
        return;
      }

      pull();
      protoContainer.writeToDirectory(new File(config.git.path).toPath().toString());
      gitRepo.add().addFilepattern(".").call();
      clean(protoContainer);
      Status status = gitRepo.status().call();
      if (status.hasUncommittedChanges()) {
        CommitCommand commit = gitRepo.commit();
        if (comment.getDescription().length() > 0) {
          commit.setMessage(comment.getDescription());
        } else {
          commit.setMessage("No message provider");
        }
        if (comment.getEmail().length() > 0 || comment.getName().length() > 0) {
          commit.setAuthor(comment.getName(), comment.getEmail());
        }
        commit.call();
        push();
        LOG.info("Git changes pushed");
      } else {
        LOG.info("No changes to commit");
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed syncing the git repo", e);
    }
  }

  private File sshPrivateKey() throws IOException {
    String privateKeyBase64 = config.git.privateKey;
    if (privateKeyBase64 == null) {
      privateKeyBase64 = global != null ? global.privateKey : null;
    }
    if (privateKeyBase64 != null) {
      File ssh = File.createTempFile(config.name, "ssh");
      try (FileOutputStream outputStream = new FileOutputStream(ssh)) {
        outputStream.write(Base64.getDecoder().decode(privateKeyBase64));
      }
      return ssh;
    }
    return null;
  }

  public void init() {
    if (config.git == null) {
      return;
    }
    LOG.info("Git Enabled");

    try {
      final File ssh = sshPrivateKey();

      if (System.getenv("DEBUG") != null && System.getenv("DEBUG").equals("true")) {
        return;
      }
      try {
        if (new File(config.git.path).exists()) {
          FileUtils.forceDelete(new File(config.git.path));
        }

        JschConfigSessionFactory sshSessionFactory =
            new JschConfigSessionFactory() {
              @Override
              protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch defaultJSch = super.createDefaultJSch(fs);
                defaultJSch.setConfig("HashKnownHosts", "yes");
                defaultJSch.setConfig("StrictHostKeyChecking", "no");
                defaultJSch.addIdentity(ssh.getPath());
                if (global.hosts != null) {
                  for (GitHostConfig host : global.hosts) {
                    defaultJSch
                        .getHostKeyRepository()
                        .add(
                            new HostKey(
                                host.host, HostKey.ECDSA256, Base64.getDecoder().decode(host.key)),
                            null);
                  }
                }
                return defaultJSch;
              }

              @Override
              protected void configure(OpenSshConfig.Host host, Session session) {
                session.setUserInfo(
                    new UserInfo() {
                      @Override
                      public String getPassphrase() {
                        return null;
                      }

                      @Override
                      public String getPassword() {
                        return null;
                      }

                      @Override
                      public boolean promptPassword(String message) {
                        return false;
                      }

                      @Override
                      public boolean promptPassphrase(String message) {
                        return false;
                      }

                      @Override
                      public boolean promptYesNo(String message) {
                        return true;
                      }

                      @Override
                      public void showMessage(String message) {}
                    });
                // do nothing
              }
            };
        transportConfigCallback =
            new TransportConfigCallback() {
              @Override
              public void configure(Transport transport) {
                SshTransport sshTransport = (SshTransport) transport;
                sshTransport.setSshSessionFactory(sshSessionFactory);
              }
            };

        LOG.info("Git Local: " + config.git.path);
        LOG.info("Git Remote: " + config.git.remote);
        this.gitRepo =
            Git.cloneRepository()
                .setURI(config.git.remote)
                .setDirectory(new File(config.git.path))
                .setTransportConfigCallback(transportConfigCallback)
                .call();
      } catch (Exception e) {
        throw new RuntimeException("Can't init local shadowCache repo", e);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
