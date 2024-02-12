package io.phasetwo.service.resource;

import static io.phasetwo.service.Orgs.ACTIVE_ORGANIZATION;
import static io.phasetwo.service.resource.Converters.*;
import static io.phasetwo.service.resource.Converters.convertOrganizationModelToOrganization;
import static io.phasetwo.service.resource.OrganizationResourceType.ORGANIZATION_ROLE_MAPPING;

import io.phasetwo.service.model.OrganizationModel;
import io.phasetwo.service.model.OrganizationRoleModel;
import io.phasetwo.service.representation.BulkResponseItem;
import io.phasetwo.service.representation.Organization;
import io.phasetwo.service.representation.OrganizationRole;
import io.phasetwo.service.representation.SwitchOrganization;
import io.phasetwo.service.util.ActiveOrganization;
import io.phasetwo.service.util.TokenManager;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import lombok.extern.jbosslog.JBossLog;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

/** */
@JBossLog
public class UserResource extends OrganizationAdminResource {

    public UserResource(KeycloakSession session) {
        super(session);
    }

    ////////
    // Users
    ////////

    @GET
    @Path("/{userId}/orgs")
    @Produces(MediaType.APPLICATION_JSON)
    public Stream<Organization> listUserOrgs(@PathParam("userId") String userId) {
        log.debugv("Get org memberships for %s %s", realm.getName(), userId);

        UserModel user = session.users().getUserById(realm, userId);
        return orgs.getUserOrganizationsStream(realm, user)
                .filter(m -> (auth.hasViewOrgs() || auth.hasOrgViewOrg(m)))
                .map(m -> convertOrganizationModelToOrganization(m));
    }

    @GET
    @Path("/{userId}/orgs/{orgId}/roles")
    @Produces(MediaType.APPLICATION_JSON)
    public Stream<OrganizationRole> listUserOrgRoles(
            @PathParam("userId") String userId, @PathParam("orgId") String orgId) {
        log.debugv("Get org roles for %s %s %s", realm.getName(), userId, orgId);

        UserModel user = session.users().getUserById(realm, userId);
        OrganizationModel org = orgs.getOrganizationById(realm, orgId);
        if (auth.hasViewOrgs() || auth.hasOrgViewRoles(org)) {
            if (org.hasMembership(user)) {
                return org.getRolesStream()
                        .filter(r -> r.hasRole(user))
                        .map(r -> convertOrganizationRole(r));
            } else {
                throw new NotFoundException("User is not a member of the organization");
            }
        } else {
            throw new NotAuthorizedException("Insufficient permissions");
        }
    }

  @PUT
  @Path("/switch-organization")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response switchActiveOrganization(
      @Valid SwitchOrganization body
  ) {

    OrganizationModel org = orgs.getOrganizationById(realm, body.getId());

    if (org == null) {
      throw new NotFoundException(String.format("%s not found", body.getId()));
    }

    if(!org.hasMembership(user)){
      throw new NotAuthorizedException("Not a member of this organization.");
    }

    // attribute based active organization
    user.setAttribute(ACTIVE_ORGANIZATION, Collections.singletonList(body.getId()));
    TokenManager tokenManager = new TokenManager(session, auth.getToken(), realm, user);

    return Response.ok(tokenManager.generateTokens()).build();
  }

  @GET
  @Path("/active-organization")
  @Produces(MediaType.APPLICATION_JSON)
  public Organization getActiveOrganization() {

    ActiveOrganization activeOrganizationUtil = new ActiveOrganization(
        session, realm, auth.getUser());

    if (!activeOrganizationUtil.hasOrganization()) {
      throw new NotFoundException("No available organizations.");
    }

    if(!activeOrganizationUtil.isValid()){
      throw new NotAuthorizedException("Action not allowed.");
    }

    return convertOrganizationModelToOrganization(activeOrganizationUtil.getActiveOrganization());
  }

    @PUT
    @Path("/{userId}/orgs/{orgId}/roles")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response grantUserOrgRoles(@PathParam("userId") String userId, @PathParam("orgId") String orgId,
                                      List<OrganizationRole> rolesRep) {
        log.debugf("Grant user organization roles for %s %s %s", realm.getName(), userId, orgId);
        UserModel user = session.users().getUserById(realm, userId);
        OrganizationModel org = orgs.getOrganizationById(realm, orgId);
        canManage(userId, orgId, user, org);

        List<BulkResponseItem> responseItems = new ArrayList<>();

        rolesRep.forEach(roleRep -> {
            int status = Response.Status.CREATED.getStatusCode();
            String error = null;
            try {
                OrganizationRoleModel role = org.getRoleByName(roleRep.getName());
                if (role == null) {
                    throw new NotFoundException(
                            String.format("Organization %s doesn't contain role %s", orgId, roleRep.getName()));
                }
                if (!role.hasRole(user)) {
                    role.grantRole(user);

                    adminEvent
                            .resource(ORGANIZATION_ROLE_MAPPING.name())
                            .operation(OperationType.CREATE)
                            .resourcePath(session.getContext().getUri())
                            .representation(userId)
                            .success();
                }
            } catch (Exception ex) {
                status = Response.Status.BAD_REQUEST.getStatusCode();
                error = ex.getMessage();
            }
            responseItems.add(new BulkResponseItem().status(status).error(error).item(roleRep));
        });

        return Response
                .status(207) //<-Multi-Status
                .location(session.getContext().getUri().getAbsolutePathBuilder().build())
                .entity(responseItems)
                .build();
    }

    @PATCH
    @Path("/{userId}/orgs/{orgId}/roles")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeUserOrgRoles(@PathParam("userId") String userId, @PathParam("orgId") String orgId,
                                       List<OrganizationRole> rolesRep) {
        log.debugf("Revoke user organization roles for %s %s %s", realm.getName(), userId, orgId);
        UserModel user = session.users().getUserById(realm, userId);
        OrganizationModel org = orgs.getOrganizationById(realm, orgId);
        canManage(userId, orgId, user, org);

        List<BulkResponseItem> responseItems = new ArrayList<>();

        rolesRep.forEach(roleRep -> {
            int status = Response.Status.NO_CONTENT.getStatusCode();
            String error = null;
            OrganizationRoleModel role = org.getRoleByName(roleRep.getName());
            try {
                if (role == null) {
                    throw new NotFoundException(String.format("Organization %s doesn't contain role %s", orgId, roleRep.getName()));
                }
                if (role.hasRole(user)) {
                    role.revokeRole(user);
                    adminEvent
                            .resource(ORGANIZATION_ROLE_MAPPING.name())
                            .operation(OperationType.DELETE)
                            .resourcePath(session.getContext().getUri())
                            .representation(userId)
                            .success();
                }
            } catch (Exception ex) {
                status = Response.Status.BAD_REQUEST.getStatusCode();
                error = ex.getMessage();
            }
            responseItems.add(new BulkResponseItem().status(status).error(error).item(roleRep));
        });
        return Response
                .status(207) //<-Multi-Status
                .location(session.getContext().getUri().getAbsolutePathBuilder().build())
                .entity(responseItems)
                .build();
    }

    private void canManage(String userId, String orgId, UserModel user, OrganizationModel org) {
        if (user == null) {
            throw new NotFoundException(String.format("User %s doesn't exist", userId));
        }
        if (org == null) {
            throw new NotFoundException(String.format("Organization %s doesn't exist", orgId));
        }
        if (!org.hasMembership(user)) {
            throw new BadRequestException(
                    String.format(
                            "User %s must be a member of %s to be granted roles.",
                            userId, org.getName()));
        }
        if (!auth.hasManageOrgs() && !auth.hasOrgManageRoles(org)) {
            throw new NotAuthorizedException("Insufficient permissions");
        }
    }
  /*
  teams is on hold for now

    @GET
    @Path("/{userId}/teams")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listUserTeams(@PathParam("userId") String userId) {
      log.debugv("Get team memberships for %s %s", realm.getName(), userId);
      Teams teams =
          mgr.getTeamsByUserId(userId).stream()
              .map(e -> convertTeamEntityToTeam(e))
              .collect(Collectors.toCollection(() -> new Teams()));
      return Response.ok().entity(teams).build();
    }
    */
}
