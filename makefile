
JSRC = \
       java/ImageResponse.java \
       java/SampCall.java \
       java/SampResult.java \
       java/TlsCredentialPresenter.java \
       java/TlsHub.java \
       java/TlsHubProfile.java \
       java/XmlRpcRelay.java \
       java/BlockingStore.java \
       java/ParsedUrl.java \
       java/HttpRequestFormat.java \
       java/RelayServlet.java \
       java/StandaloneServer.java \
       java/TlsTopcat.java \

RESOURCES = \
       protocol.txt \
       resources/index.html \
       resources/tlsamp.js \
       resources/spinger.html \
       resources/navigator.html \
       resources/monitor.html \
       resources/send.html \
       resources/slice1.png \
       resources/slice2.png \
       resources/clientIcon.gif \
       resources/messier.xml \

SAMPLOAD_FILES = sampload.html sampload.png browser-popup.png \
                 sampload sampload.jar

JSAMP_JAR = jsamp.jar
SERVLET_JAR = servlet.jar
TOPCAT_JAR = topcat-full_tlsamp.jar

# Need java 8+ to contain the right QuoVadis certificate for use with
# andromeda.star.bristol.ac.uk certificate
JAVA = java8

JARFILE = tlsamp.jar
TLSHUB = tlshub.jar
WEBAPP = tlsamp
GITVERSION = "`gitversion`"

# Keystore and password for use with the standalone server.
# If you're not going to use it (you're using the servlet instead)
# these don't need to be present.
KEYSTORE = /usr/share/tomcat/conf/andromeda2.jks
KEYPASS = `cat /usr/share/tomcat/conf/keypass.txt`

JAVAC = javac -source 1.6 -target 1.6
JFLAGS =

HTTP_DIR = /mbt/user/www/htdocs/websamp
 
build: $(JARFILE) $(JSAMP_JAR) $(TLSHUB) $(TOPCAT_JAR) $(WEBAPP).war javadocs

# This runs a standalone document server and Relay on the local machine.
# Useful for testing (you can try out TLS SAMP without a servlet container)
# but you probably wouldn't do it like this for a real deployment.
# Note you need correct certificates for the relay
# (though you could set it up using HTTP instead for testing,
# no certs required).
runserver: build
	$(JAVA) -Djavax.net.ssl.keyStore=$(KEYSTORE) \
                -Djavax.net.ssl.keyStorePassword=$(KEYPASS) \
                -classpath $(JARFILE):$(JSAMP_JAR) \
                org.astrogrid.samp.tls.StandaloneServer

# This runs an HTTPS-capable version of the the local host hub.
# You still need to deploy the relay on a server somewhere
# (either using the runserver target above or by deploying the .war
# file into a servlet container).
hub: build
	$(JAVA) -classpath $(JARFILE):$(JSAMP_JAR) \
                org.astrogrid.samp.JSamp hub -verbose \
                -profiles std,web,org.astrogrid.samp.tls.TlsHubProfile

topcat: $(TOPCAT_JAR)
	$(JAVA) -jar $(TOPCAT_JAR)

clean:
	rm -rf $(JARFILE) $(TLSHUB) $(TOPCAT_JAR) $(WEBAPP).war tmp javadocs
	rm -rf sampload sampload.jar

$(JARFILE): $(JSAMP_JAR) $(JSRC) $(RESOURCES) $(SERVLET_JAR)
	rm -rf tmp
	mkdir tmp
	mkdir -p tmp/resources
	for f in $(RESOURCES); do cp $$f tmp/resources/; done
	$(JAVAC) $(JFLAGS) -classpath $(JSAMP_JAR):$(SERVLET_JAR) \
              -d tmp $(JSRC)
	cd tmp && jar cf ../$@ . 
	rm -rf tmp

$(TLSHUB): $(JARFILE) $(JSAMP_JAR)
	rm -rf tmp
	mkdir tmp
	echo "Main-Class: org.astrogrid.samp.tls.TlsHub" > tmp/manifest
	echo "TLSAMP-version: "`gitversion` >>tmp/manifest
	cd tmp && jar xf ../$(JARFILE) org && jar xf ../$(JSAMP_JAR) org
	cd tmp && jar cmf manifest ../$@ org
	rm -rf tmp

$(JSAMP_JAR):
	cp /mbt/github/jsamp/target/jsamp-1.3.6.jar $@

$(SERVLET_JAR):
	cp /mbt/starjava/source/jetty/src/lib/javax.servlet.jar $@

$(WEBAPP).war: $(JARFILE) $(JSAMP_JAR) $(TLSHUB)
	rm -rf tmp
	mkdir tmp
	mkdir tmp/WEB-INF
	mkdir tmp/WEB-INF/lib
	cp $(JARFILE) $(JSAMP_JAR) tmp/WEB-INF/lib
	cp web.xml tmp/WEB-INF
	cp $(RESOURCES) $(TLSHUB) tmp/
	cp -r /mbt/user/www/htdocs/sampjs/ tmp/
	cd tmp && jar cf ../$@ .
	rm -rf tmp

$(TOPCAT_JAR): $(TLSHUB)
	rm -rf tmp
	mkdir -p tmp/c
	cd tmp/c \
        && curl -sL http://www.starlink.ac.uk/topcat/topcat-full.jar | jar x \
           && rm -rf META-INF \
           && jar xf ../../$(JSAMP_JAR) org \
           && jar xf ../../$(TLSHUB) org \
	   && echo "`cat uk/ac/starlink/topcat/revision-string` + TLSAMP HUB" \
                   >uk/ac/starlink/topcat/revision-string
	echo "Main-Class: org.astrogrid.samp.tls.TlsTopcat" > tmp/manifest
	echo "Permissions: all-permissions" >> tmp/manifest
	echo "Application-Name: TOPCAT" >> tmp/manifest
	cd tmp/c && jar cmf ../manifest ../../$@ *
	rm -rf tmp

sampload.jar: $(JSAMP_JAR)
	rm -rf tmp
	mkdir tmp
	cd tmp \
           && jar xf ../$(JSAMP_JAR) \
           && jar cfe ../$@ org.astrogrid.samp.util.SampLoad org
	rm -rf tmp

sampload: jarself.sh sampload.jar
	cat jarself.sh sampload.jar >$@ 
	chmod +x $@

javadocs: $(JSRC)
	rm -rf $@
	mkdir $@
	javadoc -quiet -d $@ \
                -classpath $(JARFILE):$(JSAMP_JAR):$(SERVLET_JAR) \
                $(JSRC)

deploy: deploy_http deploy_https

#  Only works as root
deploy_https: $(WEBAPP).war $(RESOURCES)
	/etc/init.d/tomcat stop
	rm -rf /usr/share/tomcat/webapps/$(WEBAPP)
	cp $(WEBAPP).war /usr/share/tomcat/webapps/
	/etc/init.d/tomcat start

deploy_http: $(RESOURCES) $(TLSHUB) $(WEBAPP).war tlshub.jnlp \
             deploy.html $(SAMPLOAD_FILES)
	rm -rf $(HTTP_DIR)
	mkdir -p $(HTTP_DIR)/examples
	cp $(RESOURCES) $(HTTP_DIR)/examples/
	cp $(WEBAPP).war $(TLSHUB) protocol.txt tlshub.jnlp $(HTTP_DIR)/
	sed -es/@gitversion@/$(GITVERSION)/g \
            < deploy.html > $(HTTP_DIR)/index.html
	cp $(SAMPLOAD_FILES) $(HTTP_DIR)
	sed -es/@gitversion@/$(GITVERSION)/g \
            < sampload.html > $(HTTP_DIR)/sampload.html


