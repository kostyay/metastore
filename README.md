# Protobuf MetaStore

## Description

Metastore is a schema registry for Protobuf and gRPC. Metastore main purpose is 
to be a hub for guarding and distributing your data contracts, be it gRPC service
or Protobuf message definitions. It's has a few supported 
[workflows](docs/workflows.md) but in general it's meant to be used in conjunction 
with a git repository and a CI/CD pipeline.

Interaction with the metastore always happens though the API. Updating of the store
could happen though CI/CD with **metastep**, a component meant for running in CI/CD.
But the API could also be used in your microservices for usage reporting. But the 
biggest benefit will come from usage in data pipelines, where they could be build
at runtime though getting the contracts over the API.

To have a clear split between configuration (through *Options*) and contract ownership
the metastore has a feature called [shadow registry](docs/shadow.md). It makes it 
possible for other parties, aside the contract owners, too augment the contracts, 
examples of this could be:

- Annotate a field to indicate a BigQuery partition column or Cluster column.
- Add descriptions to fields.
- Annotate a field to indicate it's a sensitive field (eg. Credit Cart).
- Annotate a field referencing a message to flatten it into a row.

As metastore does'nt have an API it relies on Git repositories to keep track of
contract transactions over time. Metastore is able to commit, pull and push to
Git repos where all parties can inspect changes via their favorite Git repository
manager. 

### Features

- Lint and guard against protobuf/gRPC best practices.
- Shadow registry, let other users annotate contracts with options, aside from the 
real contract owners (example data organisation).

### Documentation

- [Tutorial](docs/tutorial.md)
- [Workflows](docs/workflows.md)
- [Shadow registry](docs/shadow.md)

### Known Issues

- SSH key still need to be configured locally.
- Metastep needs to be retested and only focused on GitLab now.

## Roadmap

- Implement the resource (example Pub/Sub or Kafka topic) to contract mapping
(Message descriptor) .

## Quick setup

This quick setup is needed when you want to build Metastore by yourself, begin by
creating the file `gradle/properties.gradle`

```
ext {
    snapshotRepoUrl = "https ://you.maven.repo.example/repository/maven-snapshots/"
    repoUrl = "https ://you.maven.repo.example/repository/maven-releases/"
    repoUser = "repo_user"
    repoPass = "supersecret"

    containerRepoBase = "eu.gcr.io/my-repo"
    containerRepoTarget = "eu.gcr.io/my-repo"
}
```

Build the base images that jib uses, see README.md in base directory.

Build protobeam (metastore has a dependency, but is not published to mavenrepo) and
publish to local maven repo.