package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.TenantDto;

/**
 * @author Nabeel Ahmed
 */
public interface TenantService {

    public ResponseDto listTenants() throws Exception;

    public ResponseDto addTenant(TenantDto tenantDto) throws Exception;

    public ResponseDto updateTenant(TenantDto tenantDto) throws Exception;

    public ResponseDto changeTenantStatus(TenantDto tenantDto) throws Exception;

}
