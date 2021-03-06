package io.anemos.metastore.core.registry;

import com.google.protobuf.ByteString;
import io.anemos.metastore.config.GitGlobalConfig;
import io.anemos.metastore.config.MetaStoreConfig;
import io.anemos.metastore.config.RegistryConfig;
import io.anemos.metastore.putils.ProtoDomain;
import io.anemos.metastore.v1alpha1.RegistryP.SubmitSchemaRequest.Comment;
import io.anemos.metastore.v1alpha1.Report;
import java.io.IOException;

class SchemaRegistry extends AbstractRegistry {
  private final String name;

  public SchemaRegistry(
      Registries registries,
      MetaStoreConfig config,
      RegistryConfig registryConfig,
      GitGlobalConfig global) {
    super(registries, config, registryConfig, global);
    this.name = registryConfig.name;
  }

  @Override
  public void init() {
    if (read()) {
      write();
    }
    initGitRepo();
    syncGitRepo(Comment.newBuilder().setDescription("(Re)Sync repo").build());
  }

  @Override
  public ByteString raw() {
    return protoContainer.toByteString();
  }

  @Override
  public ProtoDomain get() {
    return protoContainer;
  }

  @Override
  public ProtoDomain ref() {
    return protoContainer;
  }

  @Override
  public void update(ProtoDomain ref, ProtoDomain in, Report report, Comment comment) {
    protoContainer = in;
    update(comment);
    syncGitRepo(comment);
    notifyEventListeners(report);
  }

  @Override
  public void update(Comment comment) {
    write();
    registries.notifyShadows(this.name, comment);
  }

  void write() {
    storageProvider.write(raw());
  }

  private boolean read() {
    try {
      ByteString buffer = storageProvider.read();
      if (buffer == null) {
        this.protoContainer = ProtoDomain.empty();
        return true;
      } else {
        this.protoContainer = ProtoDomain.buildFrom(buffer);
        return false;
      }
    } catch (IOException e) {
      throw new RuntimeException("failed to read default.pb", e);
    }
  }
}
