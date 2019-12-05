package io.anemos.metastore.metastep;

import io.anemos.metastore.core.proto.ProtocUtil;
import io.anemos.metastore.putils.ProtoDomain;
import io.anemos.metastore.v1alpha1.RegistryGrpc;
import io.anemos.metastore.v1alpha1.RegistryP;
import io.anemos.metastore.v1alpha1.Report;
import io.anemos.metastore.v1alpha1.ResultCount;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;

public class MetaStep {

  private File workspace;
  private List<String> protoIncludes;
  private RegistryGrpc.RegistryBlockingStub schemaRegistry;
  private File descriptorFile;
  private Namespace res;
  private boolean includeSource = false;

  public MetaStep(String... args) throws IOException, ArgumentParserException {
    ArgumentParser parser = ArgumentParsers.newFor("metastep").build();

    Subparsers subparsers = parser.addSubparsers().help("sub-command help");

    Subparser submitParser = subparsers.addParser("publish").help("publish help");
    submitParser.setDefault("sub-command", "publish");
    submitParser.addArgument("-p", "--package_prefix").required(true);
    submitParser.addArgument("-d", "--descriptor_set").required(false);
    submitParser.addArgument("-f", "--profile").required(false);
    submitParser.addArgument("-w", "--workspace").required(false);
    submitParser.addArgument("-s", "--server").required(true);
    submitParser.addArgument("-r", "--registry").required(false);
    submitParser.addArgument("-t", "--tls").required(false);
    submitParser.addArgument("-e", "--tls_env").required(false);
    submitParser.addArgument("-c", "--source").required(false);
    submitParser.addArgument("-i", "--include").nargs("*").required(false);

    Subparser validateParser = subparsers.addParser("validate").help("validate help");
    validateParser.setDefault("sub-command", "validate");
    validateParser.addArgument("-p", "--package_prefix").required(true);
    validateParser.addArgument("-d", "--descriptor_set").required(false);
    validateParser.addArgument("-f", "--profile").required(false);
    validateParser.addArgument("-w", "--workspace").required(false);
    validateParser.addArgument("-s", "--server").required(true);
    validateParser.addArgument("-r", "--registry").required(false);
    validateParser.addArgument("-t", "--tls").required(false);
    validateParser.addArgument("-e", "--tls_env").required(false);
    validateParser.addArgument("-c", "--source").required(false);
    validateParser.addArgument("-i", "--include").nargs("*").required(false);
    res = parser.parseArgs(args);

    descriptorFile = File.createTempFile("descriptor", ".pb");

    String server = res.getString("server");
    String[] sp = server.split(":");
    String host = sp[0];
    int port = Integer.parseInt(sp[1]);

    String protoWorkspace = res.getString("workspace");
    if (protoWorkspace == null) {
      protoWorkspace = "/var/workspace";
    }
    workspace = new File(protoWorkspace);
    System.out.println("Workspace set to: " + workspace);

    protoIncludes = res.getList("include");
    if (protoIncludes == null) {
      protoIncludes = new ArrayList<>();
    }
    protoIncludes.add("/usr/include");

    if (res.get("source") != null) {
      includeSource = true;
    }

    String tlsFileName = res.getString("tls");
    if (tlsFileName == null) {
      String tlsEnv = res.getString("tls_env");
      if (tlsEnv != null) {
        File tlsFile = File.createTempFile("tls", ".pem");
        tlsFileName = tlsFile.getAbsolutePath();
        String tlsBase64 = System.getenv(tlsEnv);
        if (tlsBase64 == null) {
          throw new RuntimeException("No ENVIRONMENT_VARIABLE of name " + tlsEnv + " found.");
        }
        try (FileOutputStream writer = new FileOutputStream(tlsFile)) {
          writer.write(Base64.getDecoder().decode(tlsBase64));
        }
      }
    }

    NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port);
    if (tlsFileName != null) {
      SslContext sslContext =
          GrpcSslContexts.forClient().trustManager(new File(tlsFileName)).build();

      channelBuilder.sslContext(sslContext).useTransportSecurity().build();
    } else {
      channelBuilder.usePlaintext();
    }
    schemaRegistry = RegistryGrpc.newBlockingStub(channelBuilder.build());
  }

  private static void printVersion() {
    Properties properties = new Properties();
    try {
      InputStream stream = MetaStep.class.getResourceAsStream("version.properties");
      if (stream != null) {
        properties.load(stream);
        System.out.println("Version: " + properties.getProperty("version"));
        System.out.println("Build Time: " + properties.getProperty("build"));
      } else {
        System.out.println("LOCAL BUILD");
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String... args) throws IOException, ArgumentParserException {
    System.out.println("MetaStep");
    URL resource = MetaStep.class.getResource("/version.properties");
    printVersion();

    if (args.length > 0 && args[0].equals("sh")) {
      GitLabMagic gitLabMagic = new GitLabMagic();

      if (gitLabMagic.gitLabEmptyCall) {
        System.out.println("Empty call ignored.");
        System.exit(0);
      }

      if (gitLabMagic.gitLabArgs == null) {
        System.err.println("! No arguments detected.");
        System.exit(1);
      } else {
        System.out.println("- Detected args: " + Arrays.asList(gitLabMagic.gitLabArgs));
      }

      if (gitLabMagic.workDir == null) {
        System.err.println("! No workdir detected.");
        System.exit(1);
      } else {
        System.out.println("- Detected workdir: " + gitLabMagic.workDir);
      }

      args =
          Stream.concat(
                  Arrays.stream(gitLabMagic.gitLabArgs),
                  Arrays.stream(new String[] {"--workspace", gitLabMagic.workDir}))
              .toArray(String[]::new);
    }

    // System.exit(0);

    MetaStep metaStep = new MetaStep(args);
    metaStep.start();
    System.err.println();
    System.err.println();
  }

  public void start() throws IOException {
    switch (res.getString("sub-command")) {
      case "validate":
        validate();
        break;
      case "publish":
        publish();
        break;
      default:
        System.out.println(res);
    }
  }

  private ProtoDomain createDescriptorSet() throws IOException {
    File file = ProtocUtil.listProtos(workspace);

    try {
      List<String> command = new ArrayList<>();
      command.add("protoc");
      if (true) {
        protoIncludes.forEach(include -> command.add("-I" + include));
        command.add("-I" + workspace.getCanonicalPath());
      }
      command.add("--descriptor_set_out=" + descriptorFile.getCanonicalPath());
      command.add("--include_imports");
      if (includeSource) {
        command.add("--include_source_info");
      }
      command.add("@" + file.getCanonicalPath());

      int pcexit = ProtocUtil.protoc(command);
      if (pcexit > 0) {
        System.exit(pcexit);
      }
    } catch (Exception err) {
      err.printStackTrace();
    }

    return ProtoDomain.buildFrom(descriptorFile);
  }

  private void validate() throws IOException {
    System.out.println("Contract Validation started");

    RegistryP.SubmitSchemaResponse verifySchemaResponse =
        schemaRegistry.verifySchema(createSchemaRequest());

    Report report = verifySchemaResponse.getReport();

    ResultCount resultCount = report.getResultCount();
    int errors = 0, warnings = 0, infos = 0;
    errors += resultCount.getDiffErrors();
    errors += resultCount.getLintErrors();
    warnings += resultCount.getLintWarnings();
    warnings += resultCount.getDiffWarnings();
    System.out.print(report);

    System.out.println(
        String.format("%d errors, %d warnings and %d infos", errors, warnings, infos));
    if (errors > 0) {
      System.err.println("Metastep failed.");
      System.exit(1);
    }
  }

  private void publish() throws IOException {
    System.out.println("Contract Push started");
    schemaRegistry.submitSchema(createSchemaRequest());
  }

  private RegistryP.SubmitSchemaRequest createSchemaRequest() throws IOException {
    ProtoDomain protoContainer = createDescriptorSet();

    RegistryP.SubmitSchemaRequest.Builder schemaRequestBuilder =
        RegistryP.SubmitSchemaRequest.newBuilder()
            .setPackagePrefix(res.getString("package_prefix"));
    protoContainer
        .iterator()
        .forEach(
            fileDescriptor ->
                schemaRequestBuilder.addFileDescriptorProto(
                    fileDescriptor.toProto().toByteString()));

    Map<String, Object> attributes = res.getAttrs();
    if (attributes.containsKey("registry") && attributes.get("registry") != null) {
      schemaRequestBuilder.setRegistryName((String) attributes.get("registry"));
    }
    if (attributes.containsKey("profile") && attributes.get("profile") != null) {
      schemaRequestBuilder.setValidationProfile((String) attributes.get("profile"));
    }
    return schemaRequestBuilder.build();
  }
}
