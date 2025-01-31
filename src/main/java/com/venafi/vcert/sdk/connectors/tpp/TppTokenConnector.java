package com.venafi.vcert.sdk.connectors.tpp;

import static java.time.Duration.ZERO;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.net.InetAddress;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharStreams;
import com.venafi.vcert.sdk.VCertException;
import com.venafi.vcert.sdk.certificate.CertificateRequest;
import com.venafi.vcert.sdk.certificate.ChainOption;
import com.venafi.vcert.sdk.certificate.CsrOriginOption;
import com.venafi.vcert.sdk.certificate.ImportRequest;
import com.venafi.vcert.sdk.certificate.ImportResponse;
import com.venafi.vcert.sdk.certificate.KeyType;
import com.venafi.vcert.sdk.certificate.PEMCollection;
import com.venafi.vcert.sdk.certificate.PublicKeyAlgorithm;
import com.venafi.vcert.sdk.certificate.RenewalRequest;
import com.venafi.vcert.sdk.certificate.RevocationRequest;
import com.venafi.vcert.sdk.certificate.SshCaTemplateRequest;
import com.venafi.vcert.sdk.certificate.SshCertRetrieveDetails;
import com.venafi.vcert.sdk.certificate.SshCertificateRequest;
import com.venafi.vcert.sdk.certificate.SshConfig;
import com.venafi.vcert.sdk.connectors.ConnectorException.AttemptToRetryException;
import com.venafi.vcert.sdk.connectors.ConnectorException.CSRNotProvidedByUserException;
import com.venafi.vcert.sdk.connectors.ConnectorException.CertificateDNOrThumbprintWasNotProvidedException;
import com.venafi.vcert.sdk.connectors.ConnectorException.CertificateNotFoundByThumbprintException;
import com.venafi.vcert.sdk.connectors.ConnectorException.CertificatePendingException;
import com.venafi.vcert.sdk.connectors.ConnectorException.CouldNotParseRevokeReasonException;
import com.venafi.vcert.sdk.connectors.ConnectorException.FailedToRevokeTokenException;
import com.venafi.vcert.sdk.connectors.ConnectorException.MissingAccessTokenException;
import com.venafi.vcert.sdk.connectors.ConnectorException.MissingCredentialsException;
import com.venafi.vcert.sdk.connectors.ConnectorException.MissingRefreshTokenException;
import com.venafi.vcert.sdk.connectors.ConnectorException.MoreThanOneCertificateWithSameThumbprintException;
import com.venafi.vcert.sdk.connectors.ConnectorException.RenewFailureException;
import com.venafi.vcert.sdk.connectors.ConnectorException.RetrieveCertificateTimeoutException;
import com.venafi.vcert.sdk.connectors.ConnectorException.RevokeFailureException;
import com.venafi.vcert.sdk.connectors.ConnectorException.TppManualCSRNotEnabledException;
import com.venafi.vcert.sdk.connectors.ConnectorException.TppPingException;
import com.venafi.vcert.sdk.connectors.ConnectorException.TppRequestCertificateNotAllowedException;
import com.venafi.vcert.sdk.connectors.Policy;
import com.venafi.vcert.sdk.connectors.ServerPolicy;
import com.venafi.vcert.sdk.connectors.TokenConnector;
import com.venafi.vcert.sdk.connectors.ZoneConfiguration;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.*;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCaTemplateRequest;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCaTemplateResponse;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCertRequest;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCertRequestResponse;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCertRetrieveRequest;
import com.venafi.vcert.sdk.connectors.tpp.endpoint.ssh.TppSshCertRetrieveResponse;
import com.venafi.vcert.sdk.endpoint.Authentication;
import com.venafi.vcert.sdk.endpoint.ConnectorType;
import com.venafi.vcert.sdk.policy.api.domain.TPPPolicy;
import com.venafi.vcert.sdk.policy.domain.PolicySpecification;
import com.venafi.vcert.sdk.policy.converter.TPPPolicySpecificationConverter;
import com.venafi.vcert.sdk.utils.Is;
import com.venafi.vcert.sdk.utils.VCertUtils;

import feign.FeignException;
import feign.FeignException.BadRequest;
import feign.FeignException.Unauthorized;
import feign.Response;
import lombok.Setter;

public class TppTokenConnector extends AbstractTppConnector implements TokenConnector {

    @Setter
    @VisibleForTesting
    private Authentication credentials;

    private TppAPI tppAPI;

    public TppTokenConnector(Tpp tpp){ super(tpp); }

    @Override
    public ConnectorType getType() {
        return ConnectorType.TPP_TOKEN;
    }

    @Override
    public void setBaseUrl(String url) throws VCertException {
        throw new UnsupportedOperationException("Method not yet implemented");
    }

    @Override
    public void setZone(String zone) {
        this.zone = zone;
    }

    @Override
    public void setVendorAndProductName(String vendorAndProductName) {
        this.vendorAndProductName = vendorAndProductName;
    }

    @Override
    public String getVendorAndProductName() {
        return vendorAndProductName;
    }

    private String getAuthHeaderValue() throws VCertException {
        if( isEmptyToken() )
        	throw new MissingAccessTokenException();

        return String.format(HEADER_VALUE_AUTHORIZATION, credentials.accessToken());
    }

    @Override
    public void ping() throws VCertException {
        Response response = doPing();
        if (response.status() != 200)
            throw new TppPingException(response.status(), response.reason());
    }

    private Response doPing() throws VCertException{
        return tpp.pingToken(getAuthHeaderValue());
    }

    @Override
    public TokenInfo getAccessToken(Authentication auth) throws VCertException {
        if(isEmptyCredentials(auth))
            throw new MissingCredentialsException();

        TokenInfo accessTokenInfo;
        try {
            AuthorizeTokenRequest authRequest =
                new AuthorizeTokenRequest(auth.user(), auth.password(), auth.clientId(), auth.scope(), auth.state(),
                    auth.redirectUri());
            AuthorizeTokenResponse response = tpp.authorizeToken(authRequest);
            accessTokenInfo = new TokenInfo(response.accessToken(), response.refreshToken(), response.expire(),
                response.tokenType(), response.scope(), response.identity(), response.refreshUntil(), true, null);

            this.credentials = auth;
            this.credentials.accessToken(accessTokenInfo.accessToken());
            this.credentials.refreshToken(accessTokenInfo.refreshToken());
        } catch(Unauthorized | BadRequest e){
            accessTokenInfo = new TokenInfo(null, null, -1, null, null,
                null, -1, false, e.getMessage() + " " + new String(e.content()) );
        }
        return accessTokenInfo;
    }

    @Override
    public TokenInfo getAccessToken() throws VCertException {
        return getAccessToken(credentials);
    }

    @Override
    public TokenInfo refreshAccessToken(String clientId ) throws VCertException{
        if(isBlank(credentials.refreshToken()))
            throw new MissingRefreshTokenException();
        
        TokenInfo tokenInfo;
        try {
            RefreshTokenRequest request = new RefreshTokenRequest(credentials.refreshToken(), clientId);
            RefreshTokenResponse response = tpp.refreshToken( request );

             tokenInfo = new TokenInfo(response.accessToken(), response.refreshToken(), response.expire(),
                    response.tokenType(), response.scope(), "", response.refreshUntil(), true, null);

            this.credentials.accessToken(tokenInfo.accessToken());
            this.credentials.refreshToken(tokenInfo.refreshToken());

            return tokenInfo;
        }catch (FeignException.BadRequest e){
            tokenInfo = new TokenInfo(null, null, -1, null, null,
                null, -1, false, e.getMessage() + " " + new String(e.content()));
        }
        return tokenInfo;
    }

    @Override
    public int revokeAccessToken() throws VCertException {
    	
        String requestHeader = getAuthHeaderValue();//"Bearer "+accessToken;

        Response response = tpp.revokeToken( requestHeader );
        if(response.status() == 200){
            return response.status();
        }else{
            throw new FailedToRevokeTokenException(response.reason());
        }
    }

    @Override
    public ZoneConfiguration readZoneConfiguration(String zone) throws VCertException {
        VCertException.throwIfNull(zone, "empty zone");
        ReadZoneConfigurationRequest request = new ReadZoneConfigurationRequest(getPolicyDN(zone));
        ReadZoneConfigurationResponse response = tpp.readZoneConfigurationToken(request, getAuthHeaderValue());
        ServerPolicy serverPolicy = response.policy();
        Policy policy = serverPolicy.toPolicy();
        ZoneConfiguration zoneConfig = serverPolicy.toZoneConfig();
        zoneConfig.policy(policy);
        zoneConfig.zoneId(zone);
        return zoneConfig;
    }

    @Override
    public CertificateRequest generateRequest(ZoneConfiguration config, CertificateRequest request) throws VCertException {
        // todo: should one really have to pass a request into a "generate request" method?
        if (config == null) {
            config = readZoneConfiguration(zone);
        }
        String tppMgmtType = config.customAttributeValues().get(TPP_ATTRIBUTE_MANAGEMENT_TYPE);
        if ("Monitoring".equals(tppMgmtType) || "Unassigned".equals(tppMgmtType))
            throw new TppRequestCertificateNotAllowedException();

        config.applyCertificateRequestDefaultSettingsIfNeeded(request);

        switch (request.csrOrigin()) {
            case LocalGeneratedCSR: {
                if ("0".equals(config.customAttributeValues().get(TPP_ATTRIBUTE_MANUAL_CSR))) 
                	throw new TppManualCSRNotEnabledException(request.csrOrigin());
                
                request.generatePrivateKey();
                request.generateCSR();
                break;
            }
            case UserProvidedCSR: {
                if ("0".equals(config.customAttributeValues().get(TPP_ATTRIBUTE_MANUAL_CSR)))
                	throw new TppManualCSRNotEnabledException(request.csrOrigin());
                
                if (Is.blank(request.csr()))
                    throw new CSRNotProvidedByUserException();
                
                break;
            }
            case ServiceGeneratedCSR: {
                request.csr(null);
                break;
            }
        }

        // TODO: should we return the request we modified? It's not a copy, it's the one that was passed in, mutated.
        return request;
    }

    @Override
    public String requestCertificate(CertificateRequest request, String zone) throws VCertException {
        return requestCertificate(request, new ZoneConfiguration().zoneId(zone));
    }

    @Override
    public String requestCertificate(CertificateRequest request, ZoneConfiguration zoneConfiguration)
            throws VCertException {
        if (isBlank(zoneConfiguration.zoneId())) {
            zoneConfiguration.zoneId(this.zone);
        }
        CertificateRequestsPayload payload = prepareRequest(request, zoneConfiguration.zoneId());
        Tpp.CertificateRequestResponse response = tpp.requestCertificateToken(payload, getAuthHeaderValue());
        String requestId = response.certificateDN();
        request.pickupId(requestId);
        return requestId;
    }

    private CertificateRequestsPayload prepareRequest(CertificateRequest request, String zone)
            throws VCertException {
        CertificateRequestsPayload payload;
        Collection<NameValuePair<String, String>> caSpecificAttributes =
                new ArrayList<NameValuePair<String, String>>();

        // Workaround to send Origin to TPP versions that does not support it in the payload
        if (!isBlank(vendorAndProductName)) {
            caSpecificAttributes.add(new NameValuePair<String, String>("Origin", vendorAndProductName));
        }

        switch (request.csrOrigin()) {
            case LocalGeneratedCSR:
                payload = new CertificateRequestsPayload().policyDN(getPolicyDN(zone))
                        .pkcs10(new String(request.csr())).objectName(request.friendlyName())
                        .disableAutomaticRenewal(true).origin(vendorAndProductName)
                        .caSpecificAttributes(caSpecificAttributes);
                break;
            case UserProvidedCSR:
                payload = new CertificateRequestsPayload().policyDN(getPolicyDN(zone))
                        .pkcs10(new String(request.csr())).objectName(request.friendlyName())
                        .subjectAltNames(wrapAltNames(request)).disableAutomaticRenewal(true)
                        .origin(vendorAndProductName).caSpecificAttributes(caSpecificAttributes);
                break;
            case ServiceGeneratedCSR:
                payload = new CertificateRequestsPayload().policyDN(getPolicyDN(zone))
                        .objectName(request.friendlyName()).subject(request.subject().commonName()) // TODO (Go
                        // SDK):
                        // there is
                        // some
                        // problem
                        // because
                        // Subject
                        // is not
                        // only CN
                        .subjectAltNames(wrapAltNames(request)).disableAutomaticRenewal(true)
                        .origin(vendorAndProductName).caSpecificAttributes(caSpecificAttributes);
                break;
            default:
                throw new VCertException(MessageFormat.format("Unexpected option in PrivateKeyOrigin: {0}",
                        request.csrOrigin()));
        }

        if (request.keyType() == null) {
            request.keyType(KeyType.defaultKeyType());
        }

        switch (request.keyType()) {
            case RSA: {
                payload.keyAlgorithm(PublicKeyAlgorithm.RSA.name());
                payload.keyBitSize(request.keyLength());
                break;
            }
            case ECDSA: {
                payload.keyAlgorithm("ECC");
                payload.ellipticCurve(request.keyCurve().value());
                break;
            }
        }
        
        //support for validity hours begins
        VCertUtils.addExpirationDateAttribute(request, payload);
       //support for validity hours ends
        
        
        //support for custom fields begins
        VCertUtils.addCustomFieldsToRequest(request, payload);
        //support for custom fields ends
        
        return payload;
    }

    private Collection<SANItem> wrapAltNames(CertificateRequest request) {
        List<SANItem> sanItems = new ArrayList<>();
        sanItems.addAll(toSanItems(request.emailAddresses(), 1));
        sanItems.addAll(toSanItems(request.dnsNames(), 2));
        sanItems.addAll(toSanItems(request.ipAddresses(), 7));
        return sanItems;
    }

    private List<SANItem> toSanItems(Collection<?> collection, int type) {
        return Optional.ofNullable(collection).orElse(Collections.emptyList()).stream()
                .filter(Objects::nonNull)
                .map(entry -> new SANItem().type(type)
                        .name(type == 7 ? ((InetAddress) entry).getHostAddress() : entry.toString()))
                .collect(toList());
    }

    @Override
    public PEMCollection retrieveCertificate(CertificateRequest request) throws VCertException {
        boolean includeChain = request.chainOption() != ChainOption.ChainOptionIgnore;
        boolean rootFirstOrder =
                includeChain && request.chainOption() == ChainOption.ChainOptionRootFirst;

        if (isNotBlank(request.pickupId()) && isNotBlank(request.thumbprint())) {
            Tpp.CertificateSearchResponse searchResult =
                    searchCertificatesByFingerprint(request.thumbprint());
            if (searchResult.certificates().size() == 0)
            	throw new CertificateNotFoundByThumbprintException(request.thumbprint());
            
            if (searchResult.certificates().size() > 1) 
                throw new MoreThanOneCertificateWithSameThumbprintException(request.thumbprint());
            
            request.pickupId(searchResult.certificates().get(0).certificateRequestId());
        }

        CertificateRetrieveRequest certReq =
                new CertificateRetrieveRequest().certificateDN(request.pickupId()).format("base64")
                        .rootFirstOrder(rootFirstOrder).includeChain(includeChain);

        if (request.csrOrigin() == CsrOriginOption.ServiceGeneratedCSR || request.fetchPrivateKey()) {
            certReq.includePrivateKey(true);
            certReq.password(request.keyPassword());
        }

        // TODO move this retry logic to feign client
        Instant startTime = Instant.now();
        while (true) {
            Tpp.CertificateRetrieveResponse retrieveResponse = retrieveCertificateOnce(certReq);
            if (isNotBlank(retrieveResponse.certificateData())) {
                PEMCollection pemCollection = PEMCollection.fromResponse(
                        org.bouncycastle.util.Strings
                                .fromByteArray(Base64.getDecoder().decode(retrieveResponse.certificateData())),
                        request.chainOption(), request.privateKey(), request.keyPassword());
                request.checkCertificate(pemCollection.certificate());
                return pemCollection;
            }

            if (ZERO.equals(request.timeout()))
                throw new CertificatePendingException(request.pickupId());

            if (Instant.now().isAfter(startTime.plus(request.timeout())))
                throw new RetrieveCertificateTimeoutException(request.pickupId());

            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new AttemptToRetryException(e);
            }
        }
    }

    private Tpp.CertificateRetrieveResponse retrieveCertificateOnce(
            CertificateRetrieveRequest certificateRetrieveRequest) throws VCertException {
        return tpp.certificateRetrieveToken(certificateRetrieveRequest, getAuthHeaderValue());
    }


    private Tpp.CertificateSearchResponse searchCertificatesByFingerprint(String fingerprint) throws VCertException {
        final Map<String, String> searchRequest = new HashMap<String, String>();
        searchRequest.put("Thumbprint", fingerprint);

        return searchCertificates(searchRequest);
    }

    private Tpp.CertificateSearchResponse searchCertificates(Map<String, String> searchRequest) throws VCertException {
        return tpp.searchCertificatesToken(searchRequest, getAuthHeaderValue());
    }

    @Override
    public void revokeCertificate(RevocationRequest request) throws VCertException {
        Integer reason = revocationReasons.get(request.reason());
        if (reason == null)
            throw new CouldNotParseRevokeReasonException(request.reason());

        CertificateRevokeRequest revokeRequest = new CertificateRevokeRequest()
                .certificateDN(request.certificateDN()).thumbprint(request.thumbprint()).reason(reason)
                .comments(request.comments()).disable(request.disable());

        Tpp.CertificateRevokeResponse revokeResponse = revokeCertificate(revokeRequest);
        if (!revokeResponse.success())
            throw new RevokeFailureException(revokeResponse.error());
    }

    private Tpp.CertificateRevokeResponse revokeCertificate(CertificateRevokeRequest request) throws VCertException {
        return tpp.revokeCertificateToken(request, getAuthHeaderValue());
    }

    @Override
    public String renewCertificate(RenewalRequest request) throws VCertException {
        String certificateDN;

        if (isNotBlank(request.thumbprint()) && isBlank(request.certificateDN())) {
            Tpp.CertificateSearchResponse searchResult =
                    searchCertificatesByFingerprint(request.thumbprint());
            if (searchResult.certificates().isEmpty())
                throw new CertificateNotFoundByThumbprintException(request.thumbprint());
            
            if (searchResult.certificates().size() > 1)
                throw new MoreThanOneCertificateWithSameThumbprintException(request.thumbprint());
            
            certificateDN = searchResult.certificates().get(0).certificateRequestId();
        } else {
            certificateDN = request.certificateDN();
        }

        if (isNull(certificateDN))
            throw new CertificateDNOrThumbprintWasNotProvidedException();

        final CertificateRenewalRequest renewalRequest = new CertificateRenewalRequest();
        renewalRequest.certificateDN(certificateDN);

        if (nonNull(request.request()) && nonNull(request.request().csr()) && request.request().csr().length > 0) {
            String pkcs10 = org.bouncycastle.util.Strings.fromByteArray(request.request().csr());
            renewalRequest.PKCS10(pkcs10);
        }

        final Tpp.CertificateRenewalResponse response = tpp.renewCertificateToken(renewalRequest, getAuthHeaderValue());
        if (!response.success())
            throw new RenewFailureException(response.error());

        return certificateDN;
    }


    @Override
    public ImportResponse importCertificate(ImportRequest request) throws VCertException {
        if (isBlank(request.policyDN())) {
            request.policyDN(getPolicyDN(zone));
        }

        return doImportCertificate(request);
    }

    private ImportResponse doImportCertificate(ImportRequest request) throws VCertException {
        return tpp.importCertificateToken(request, getAuthHeaderValue());
    }

    @Override
    public Policy readPolicyConfiguration(String zone) throws VCertException {
        throw new UnsupportedOperationException("Method not yet implemented");
    }

    @Override
    public void setPolicy(String policyName, PolicySpecification policySpecification) throws VCertException {
        try {
            TPPPolicy tppPolicy = TPPPolicySpecificationConverter.INSTANCE.convertFromPolicySpecification(policySpecification);
            setPolicy(policyName, tppPolicy);
        }catch (Exception e){
            throw new VCertException(e);
        }
    }

    @Override
    public PolicySpecification getPolicy(String policyName) throws VCertException {
        PolicySpecification policySpecification;
        try {
            TPPPolicy tppPolicy = getTPPPolicy(policyName);

            policySpecification = TPPPolicySpecificationConverter.INSTANCE.convertToPolicySpecification( tppPolicy );

        }catch (Exception e){
            throw new VCertException(e);
        }

        return policySpecification;
    }
    
	@Override
	public String requestSshCertificate(SshCertificateRequest sshCertificateRequest) throws VCertException {
		
		TppSshCertRequestResponse tppSshCertRequestResponse = super.requestTppSshCertificate(sshCertificateRequest);
		
		return tppSshCertRequestResponse.dn();
	}

	@Override
	public SshCertRetrieveDetails retrieveSshCertificate(SshCertificateRequest sshCertificateRequest)
			throws VCertException {
		
		return super.retrieveTppSshCertificate(sshCertificateRequest);
	}
	
	@Override
	public SshConfig retrieveSshConfig(SshCaTemplateRequest sshCaTemplateRequest) throws VCertException {
		return super.retrieveTppSshConfig(sshCaTemplateRequest);
	}

    private boolean isEmptyCredentials(Authentication credentials){
        if(credentials == null){
            return true;
        }

        if(credentials.user() == null || credentials.user().isEmpty()){
            return true;
        }

        if(credentials.password() == null || credentials.password().isEmpty()){
            return true;
        }

        return false;
    }

    private boolean isEmptyToken(){
        if(credentials == null || isBlank(credentials.accessToken())){
            return true;
        }

        return false;
    }

    @Override
    protected TppAPI getTppAPI() {
        if(tppAPI == null){

            tppAPI = new TppAPI(tpp) {

            	@Override
                public String getAuthKey() throws VCertException {
                    return getAuthHeaderValue();
                }

                @Override
                public DNIsValidResponse dnIsValid(DNIsValidRequest request) throws VCertException {
                    return tpp.dnIsValidToken(request, getAuthKey());
                }

                @Override
                CreateDNResponse createDN(CreateDNRequest request) throws VCertException {
                    return tpp.createDNToken(request, getAuthKey());
                }

                @Override
                SetPolicyAttributeResponse setPolicyAttribute(SetPolicyAttributeRequest request) throws VCertException {
                    return tpp.setPolicyAttributeToken(request, getAuthKey());
                }

                @Override
                GetPolicyAttributeResponse getPolicyAttribute(GetPolicyAttributeRequest request) throws VCertException {
                    return tpp.getPolicyAttributeToken(request, getAuthKey());
                }

                @Override
                GetPolicyResponse getPolicy(GetPolicyRequest request) throws VCertException {
                    return tpp.getPolicyToken(request, getAuthKey());
                }

                @Override
                Response clearPolicyAttribute(ClearPolicyAttributeRequest request) throws VCertException {
                    return tpp.clearPolicyAttributeToken(request, getAuthKey());
                }

        		@Override
        		TppSshCertRequestResponse requestSshCertificate(TppSshCertRequest request) throws VCertException {
        			return tpp.requestSshCertificateToken(request, getAuthKey());
        		}

        		@Override
        		TppSshCertRetrieveResponse retrieveSshCertificate(TppSshCertRetrieveRequest request) throws VCertException {
        			return tpp.retrieveSshCertificateToken(request, getAuthKey());
        		}

				@Override
				String retrieveSshCAPublicKeyData(Map<String, String> params) throws VCertException {
					String publicKeyData = null;

					try {
						publicKeyData = CharStreams.toString(tpp.retrieveSshCAPublicKeyDataToken(params).body().asReader());
					} catch (Exception e) {
						throw new VCertException(e);
					}

					return publicKeyData;
				}

				@Override
				TppSshCaTemplateResponse retrieveSshCATemplate(TppSshCaTemplateRequest request) throws VCertException {
					return tpp.retrieveSshCATemplateToken(request, getAuthKey());
				}
            };
        }

        return tppAPI;
    }
}
