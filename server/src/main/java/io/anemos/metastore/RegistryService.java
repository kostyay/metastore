package io.anemos.metastore;

import static io.anemos.metastore.v1alpha1.Registry.GetResourceBindingeRequest.SchemaContext;

import com.google.protobuf.Descriptors;
import io.anemos.metastore.core.proto.PContainer;
import io.anemos.metastore.core.proto.profile.ProfileAvroEvolve;
import io.anemos.metastore.core.proto.profile.ValidationProfile;
import io.anemos.metastore.core.proto.validate.ProtoDiff;
import io.anemos.metastore.core.proto.validate.ProtoLint;
import io.anemos.metastore.core.proto.validate.ValidationResults;
import io.anemos.metastore.core.registry.AbstractRegistry;
import io.anemos.metastore.v1alpha1.Registry;
import io.anemos.metastore.v1alpha1.RegistyGrpc;
import io.anemos.metastore.v1alpha1.Report;
import io.anemos.metastore.v1alpha1.ResultCount;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class RegistryService extends RegistyGrpc.RegistyImplBase {

  private MetaStore metaStore;

  public RegistryService(MetaStore metaStore) {
    this.metaStore = metaStore;
  }

  @Override
  public void submitSchema(
      Registry.SubmitSchemaRequest request,
      StreamObserver<Registry.SubmitSchemaResponse> responseObserver) {
    schema(request, responseObserver, true);
  }

  @Override
  public void verifySchema(
      Registry.SubmitSchemaRequest request,
      StreamObserver<Registry.SubmitSchemaResponse> responseObserver) {
    schema(request, responseObserver, false);
  }

  public void schema(
      Registry.SubmitSchemaRequest request,
      StreamObserver<Registry.SubmitSchemaResponse> responseObserver,
      boolean submit) {
    PContainer in;
    try {
      in = new PContainer(request.getFileDescriptorProtoList());
    } catch (IOException e) {
      responseObserver.onError(
          Status.fromCode(Status.Code.INVALID_ARGUMENT)
              .withDescription("Invalid FileDescriptor Set.")
              .withCause(e)
              .asRuntimeException());
      return;
    }

    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      Report report = validate(registry, request, registry.get(), in);

      if (submit) {
        if (hasErrors(report)) {
          responseObserver.onError(
              Status.fromCode(Status.Code.FAILED_PRECONDITION)
                  .withDescription("Incompatible schema, us verify to get errors.")
                  .asRuntimeException());
          return;
        }
        registry.update(registry.ref(), in, report);
      }

      responseObserver.onNext(Registry.SubmitSchemaResponse.newBuilder().setReport(report).build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  private boolean hasErrors(Report report) {
    if (report.hasResultCount()) {
      ResultCount resultCount = report.getResultCount();
      return resultCount.getDiffErrors() > 0 || resultCount.getLintErrors() > 0;
    }
    return false;
  }

  private Report validate(
      AbstractRegistry registry,
      Registry.SubmitSchemaRequest request,
      PContainer ref,
      PContainer in)
      throws StatusException {
    ValidationResults results = new ValidationResults();
    ProtoDiff diff = new ProtoDiff(ref, in, results);
    ProtoLint lint = new ProtoLint(in, results);

    switch (request.getEntityScopeCase()) {
      case ENTITYSCOPE_NOT_SET:
        diff.diffOnPackagePrefix("");
        lint.lintOnPackagePrefix("");
        break;
      case PACKAGE_PREFIX:
        diff.diffOnPackagePrefix(request.getPackagePrefix());
        lint.lintOnPackagePrefix(request.getPackagePrefix());
        break;
      case PACKAGE_NAME:
        break;
      case MESSAGE_NAME:
        lint.lintOnMessage(request.getMessageName());
        break;
      case SERVICE_NAME:
        lint.lintOnService(request.getServiceName());
        break;
      case ENUM_NAME:
        lint.lintOnEnum(request.getEnumName());
        break;
      case FILE_NAME:
        diff.diffOnFileName(request.getFileName());
        lint.lintOnFileName(request.getFileName());
        break;
      case LINKED_RESOURCE:
        Registry.ResourceBinding resourceBinding =
            registry.getResourceBinding(request.getLinkedResource());
        switch (resourceBinding.getTypeCase()) {
          case MESSAGE_NAME:
            lint.lintOnMessage(request.getMessageName());
            break;
          case SERVICE_NAME:
            lint.lintOnService(request.getMessageName());
            break;
          case TYPE_NOT_SET:
          default:
            throw Status.fromCode(Status.Code.INTERNAL).asRuntimeException();
        }
        break;
      default:
        throw Status.fromCode(Status.Code.INTERNAL).asRuntimeException();
    }

    ValidationProfile profile = new ProfileAvroEvolve();
    return profile.validate(results.getReport());
  }

  @Override
  public void getSchema(
      Registry.GetSchemaRequest request,
      StreamObserver<Registry.GetSchemaResponse> responseObserver) {
    try {
      Registry.GetSchemaResponse.Builder schemaResponseBuilder =
          Registry.GetSchemaResponse.newBuilder();

      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      PContainer pContainer = registry.get();

      List<Descriptors.FileDescriptor> fdl = new ArrayList<>();
      switch (request.getEntityScopeCase()) {
        case PACKAGE_PREFIX:
          fdl = pContainer.getFileDescriptorsByPackagePrefix(request.getPackagePrefix());
          break;
        case PACKAGE_NAME:
          fdl = pContainer.getFileDescriptorsByPackageName(request.getPackageName());
          break;
        case MESSAGE_NAME:
          Descriptors.Descriptor descriptor =
              pContainer.getDescriptorByName(request.getMessageName());
          if (descriptor != null) {
            fdl.add(descriptor.getFile());
          }
          break;
        case SERVICE_NAME:
          Descriptors.ServiceDescriptor serviceDescriptor =
              pContainer.getServiceDescriptorByName(request.getServiceName());
          if (serviceDescriptor != null) {
            fdl.add(serviceDescriptor.getFile());
          }
          break;
        case ENUM_NAME:
          Descriptors.EnumDescriptor enumDescriptor =
              pContainer.getEnumDescriptorByName(request.getServiceName());
          if (enumDescriptor != null) {
            fdl.add(enumDescriptor.getFile());
          }
          break;
        case FILE_NAME:
          Descriptors.FileDescriptor fileDescriptor =
              pContainer.getFileDescriptorByFileName(request.getFileName());
          if (fileDescriptor != null) {
            fdl.add(fileDescriptor);
          }
          break;
        case LINKED_RESOURCE:
          Registry.ResourceBinding resourceBinding =
              registry.getResourceBinding(request.getLinkedResource());
          switch (resourceBinding.getTypeCase()) {
            case MESSAGE_NAME:
              Descriptors.Descriptor linkedDescriptor =
                  pContainer.getDescriptorByName(resourceBinding.getMessageName());
              if (linkedDescriptor == null) {
                throw Status.fromCode(Status.Code.NOT_FOUND)
                    .withDescription("Message referenced in binding not found.")
                    .asException();
              }
              fdl.add(linkedDescriptor.getFile());
              break;
            case SERVICE_NAME:
              Descriptors.ServiceDescriptor linkedServiceDescriptor =
                  pContainer.getServiceDescriptorByName(resourceBinding.getServiceName());
              if (linkedServiceDescriptor == null) {
                throw Status.fromCode(Status.Code.NOT_FOUND)
                    .withDescription("Service referenced in binding not found.")
                    .asException();
              }
              fdl.add(linkedServiceDescriptor.getFile());
              break;
            case TYPE_NOT_SET:
            default:
              throw Status.fromCode(Status.Code.INTERNAL).asRuntimeException();
          }
          break;
        case ENTITYSCOPE_NOT_SET:
          fdl = new ArrayList<>();
          break;
        default:
          throw Status.fromCode(Status.Code.INTERNAL).asRuntimeException();
      }
      if (fdl.size() == 0) {
        throw Status.fromCode(Status.Code.NOT_FOUND)
            .withDescription("No descriptors matching the search criteria.")
            .asException();
      }
      if (request.getTransitive()) {
        schemaResponseBuilder.addAllFileDescriptorProto(
            pContainer.getDependantFileDescriptors(fdl).stream()
                .map(fd -> fd.toProto().toByteString())
                .collect(Collectors.toList()));
      } else {
        schemaResponseBuilder.addAllFileDescriptorProto(
            fdl.stream().map(fd -> fd.toProto().toByteString()).collect(Collectors.toList()));
      }

      responseObserver.onNext(schemaResponseBuilder.build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void createResourceBinding(
      Registry.CreateResourceBindingRequest request,
      StreamObserver<Registry.CreateResourceBindingResponse> responseObserver) {
    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      Registry.ResourceBinding resourceBinding = request.getBinding();
      registry.updateResourceBinding(resourceBinding, true);
      responseObserver.onNext(Registry.CreateResourceBindingResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void updateResourceBinding(
      Registry.UpdateResourceBindingRequest request,
      StreamObserver<Registry.UpdateResourceBindingResponse> responseObserver) {
    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      Registry.ResourceBinding resourceBinding = request.getBinding();
      registry.updateResourceBinding(resourceBinding, false);
      responseObserver.onNext(Registry.UpdateResourceBindingResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void getResourceBinding(
      Registry.GetResourceBindingeRequest request,
      StreamObserver<Registry.GetResourceBindingResponse> responseObserver) {
    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      Registry.ResourceBinding resourceBinding =
          registry.getResourceBinding(request.getLinkedResource());

      Registry.GetResourceBindingResponse.Builder response =
          Registry.GetResourceBindingResponse.newBuilder().setBinding(resourceBinding);

      PContainer pContainer = registry.get();
      if (request.getSchemaContext() == SchemaContext.SCHEMA_CONTEXT_FULL_DOMAIN) {
        response.addAllFileDescriptorProto(
            pContainer.getFileDescriptors().stream()
                .map(fd -> fd.toProto().toByteString())
                .collect(Collectors.toList()));
      } else if (request.getSchemaContext() == SchemaContext.SCHEMA_CONTEXT_IN_SCOPE) {
        Collection<Descriptors.FileDescriptor> fds = new ArrayList<>();
        switch (resourceBinding.getTypeCase().getNumber()) {
          case Registry.ResourceBinding.MESSAGE_NAME_FIELD_NUMBER:
            Descriptors.Descriptor descriptor =
                pContainer.getDescriptorByName(resourceBinding.getMessageName());
            fds = pContainer.getDependantFileDescriptors(descriptor.getFile());
            break;
          case Registry.ResourceBinding.SERVICE_NAME_FIELD_NUMBER:
            Descriptors.ServiceDescriptor service =
                pContainer.getServiceDescriptorByName(resourceBinding.getServiceName());
            fds = pContainer.getDependantFileDescriptors(service.getFile());
            break;
          default:
            throw Status.fromCode(Status.Code.INTERNAL)
                .withDescription("Linked resource isn't linked to a descriptor")
                .asRuntimeException();
        }
        response.addAllFileDescriptorProto(
            fds.stream().map(fd -> fd.toProto().toByteString()).collect(Collectors.toList()));
      } else if (request.getSchemaContext() == SchemaContext.SCHEMA_CONTEXT_IN_FILE) {
        switch (resourceBinding.getTypeCase().getNumber()) {
          case Registry.ResourceBinding.MESSAGE_NAME_FIELD_NUMBER:
            Descriptors.Descriptor descriptor =
                pContainer.getDescriptorByName(resourceBinding.getMessageName());
            response.addFileDescriptorProto(descriptor.getFile().toProto().toByteString());
            break;
          case Registry.ResourceBinding.SERVICE_NAME_FIELD_NUMBER:
            Descriptors.ServiceDescriptor service =
                pContainer.getServiceDescriptorByName(resourceBinding.getServiceName());
            response.addFileDescriptorProto(service.getFile().toProto().toByteString());
            break;
          default:
            throw Status.fromCode(Status.Code.INTERNAL)
                .withDescription("Linked resource isn't linked to a descriptor")
                .asRuntimeException();
        }
      }
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void deleteResourceBinding(
      Registry.DeleteResourceBindingRequest request,
      StreamObserver<Registry.DeleteResourceBindingResponse> responseObserver) {
    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      registry.deleteResourceBinding(request.getLinkedResource());
      responseObserver.onNext(Registry.DeleteResourceBindingResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void listResourceBindings(
      Registry.ListResourceBindingsRequest request,
      StreamObserver<Registry.ListResourceBindingsResponse> responseObserver) {
    try {
      AbstractRegistry registry = metaStore.registries.get(request.getRegistryName());
      Registry.ListResourceBindingsResponse.Builder builder =
          Registry.ListResourceBindingsResponse.newBuilder();
      registry
          .listResourceBindings(request.getPageToken())
          .forEach(
              binding -> {
                builder.addBindings(binding);
              });
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }
}
