FROM eclipse-temurin:25.0.4_7-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends python3 curl && rm -rf /var/lib/apt/lists/*
WORKDIR /hmc
ARG HEADLESSMC
RUN curl -fsSL -o headlessmc.jar "$HEADLESSMC" \
    && java -jar headlessmc.jar config set hmc.jline.enabled false \
    && java -jar headlessmc.jar config set hmc.assets.dummy true \
    && java -jar headlessmc.jar version install fabric 26.3
ARG MODS
RUN mkdir mods && cd mods && for url in $MODS; do curl -fsSLO "$url"; done
COPY run.sh e2e_agent.py ./
ENTRYPOINT ["sh", "/hmc/run.sh"]
