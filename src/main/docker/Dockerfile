FROM alpine

EXPOSE 22 3690 8123

COPY ["install/git-as-svn/bin", "/usr/share/git-as-svn/bin"]
COPY ["install/git-as-svn/lib", "/usr/share/git-as-svn/lib"]
COPY ["install/git-as-svn/tools/git-as-svn-svnserve", \
      "install/git-as-svn/tools/git-as-svn-svnserve-tunnel", \
      "/usr/bin/"]
COPY ["docker-files", "/"]
COPY ["docs/asciidoc/examples/config.yml", "/usr/share/git-as-svn/"]

ENV GAS_USER git
ENV GAS_GROUP git

RUN apk add --no-cache \
        gettext \
        openjdk11-jre-headless \
        openssh \
        s6 \
        && \
    rm /etc/ssh/sshd_config && \
    ln -s /usr/share/git-as-svn/bin/git-as-svn /usr/bin/. && \
    addgroup -S $GAS_GROUP && \
    adduser -S -D -h /data -s /bin/ash -g git-as-svn -G $GAS_GROUP $GAS_USER && \
    sed -i -e "s/^$GAS_USER:x:/$GAS_USER:*:/g" /etc/passwd

VOLUME ["/data", \
        "/var/git/repositories", \
        "/var/git/hooks", \
        "/var/git/ssh-shadow", \
        "/var/git/ssh-real", \
        "/var/git/lfs-objects"]

ENTRYPOINT ["/bin/entrypoint"]
