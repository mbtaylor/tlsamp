
JSRC = \
       java/ImageResponse.java \
       java/SampCall.java \
       java/SampResult.java \
       java/TlsAuthHeaderControl.java \
       java/TlsHub.java \
       java/TlsHubProfile.java \
       java/XmlRpcRelay.java \
       java/BlockingStore.java \
       java/RelayServlet.java \
       java/StandaloneServer.java \

RESOURCES = \
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

JSAMP_JAR = jsamp.jar
SERVLET_JAR = servlet.jar

# Need java 7+ to contain the right QuoVadis certificate for use with
# andromeda.star.bristol.ac.uk certificate
JAVA = java7

JARFILE = tlsamp.jar
TLSHUB = tlshub.jar
WEBAPP = tlsamp

# Keystore and password for use witt the standalone server.
# If you're not going to use it (you're using the servlet instead)
# these don't need to be present.
KEYSTORE = /usr/share/tomcat/conf/keystore.jks
KEYPASS = `cat /usr/share/tomcat/conf/keypass.txt`

JFLAGS =

HTTP_DIR = /mbt/user/www/htdocs/websamp
 
build: $(JARFILE) $(JSAMP_JAR) $(TLSHUB) $(WEBAPP).war

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

topcat:
	$(JAVA) -jar /mbt/starjava/lib/topcat/topcat.jar

clean:
	rm -rf $(JARFILE) $(TLSHUB) $(WEBAPP).war tmp

$(JARFILE): $(JSAMP_JAR) $(JSRC) $(RESOURCES) $(SERVLET_JAR)
	rm -rf tmp
	mkdir tmp
	mkdir -p tmp/resources
	for f in $(RESOURCES); do cp $$f tmp/$$f; done
	javac $(JFLAGS) -classpath $(JSAMP_JAR):$(SERVLET_JAR) \
              -d tmp $(JSRC)
	cd tmp && jar cf ../$@ . 
	rm -rf tmp

$(TLSHUB): $(JARFILE) $(JSAMP_JAR)
	rm -rf tmp
	mkdir tmp
	echo "Main-Class: org.astrogrid.samp.tls.TlsHub" > tmp/manifest
	cd tmp && jar xf ../$(JARFILE) org && jar xf ../$(JSAMP_JAR) org
	cd tmp && jar cmf manifest ../$@ org
	rm -rf tmp

$(JSAMP_JAR):
	cp /mbt/github/jsamp/target/jsamp-1.3.5+.jar $@

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
	cd tmp && jar cf ../$@ .
	rm -rf tmp

deploy: deploy_http deploy_https

#  Only works as root
deploy_https: $(WEBAPP).war $(RESOURCES)
	/etc/init.d/tomcat stop
	rm -rf /usr/share/tomcat/webapps/$(WEBAPP)
	cp $(WEBAPP).war /usr/share/tomcat/webapps/
	/etc/init.d/tomcat start

deploy_http: $(RESOURCES) $(TLSHUB) $(WEBAPP).war deploy.html protocol.txt
	rm -rf $(HTTP_DIR)/examples
	mkdir $(HTTP_DIR)/examples
	cp $(RESOURCES) $(HTTP_DIR)/examples/
	cp $(WEBAPP).war $(TLSHUB) protocol.txt $(HTTP_DIR)/
	cp deploy.html $(HTTP_DIR)/index.html


