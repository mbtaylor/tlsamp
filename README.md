# tlsamp
Experiments with SAMP over HTTPS

This project contains some bits and pieces that can be used 
to get SAMP working from an HTTPS hosted web application.
I'm calling this variously SAMP over HTTPS, the SAMP TLS Profile and tlsamp.

For some background see the following presentations:

   * [Sydney 2015](http://wiki.ivoa.net/internal/IVOA/InteropOct2015Applications/samp-https.pdf)
   * [Edinburgh 2016](https://www.asterics2020.eu/dokuwiki/lib/exe/fetch.php?media=open:wp4:tlsamp.pdf)

The main components are:

   * `tlsamp.js`: javascript client library, a modification of the `samp.js` library to with the the TLS profile
   * Some simple web applications that make use of tlsamp.js to show TLS SAMP working
   * A proxy hub implementation that sits on a remote host
     (likely, but not necessarily, the one hosting the web application) 
     and fowards messages from the (localhost) client to the (localhost) hub. 
     At some places in the code this is known as the "bouncer".
      * A standalone harness for the proxy hub
      * A servlet harness for the proxy hub
   * A Hub Profile that can be used with the JSAMP hub that knows how to talk to
     the Proxy Hub, and also to sandboxed clients running on the local host

To make these useful/usable the makefile will build a couple of extra tthngs::
   * A .war file you can drop into a servlet container (like tomcat)
     This contains the running proxy hub as well as some example web applications
   * A hub you can run on the local host that uses the TLS profile

You can see some of this stuff running on my mmachine:
   [http://andromeda.star.bristol.ac.uk/websamp/](http://andromeda.star.bristol.ac.uk/websamp/)

All the code here, and the prototype APIs/protocols that it represents,
are highly experimental and subject to change.
It's not clear yet what the design details should be,
or even whether TLS SAMP is a good idea.
If you grab a version one day and then get bits of it later, they
may not work together.  You have been warned!  But I'll try not
to change things too gratuitously.

Mark Taylor
