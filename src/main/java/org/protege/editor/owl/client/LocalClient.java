package org.protege.editor.owl.client;

import edu.stanford.protege.metaproject.api.*;
import edu.stanford.protege.metaproject.api.exception.IdAlreadyInUseException;
import edu.stanford.protege.metaproject.impl.Operations;
import org.protege.editor.owl.client.api.Client;
import org.protege.editor.owl.client.api.UserInfo;
import org.protege.editor.owl.client.api.exception.ClientRequestException;
import org.protege.editor.owl.client.api.exception.SynchronizationException;
import org.protege.editor.owl.client.util.ServerUtils;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.OutOfSyncException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.transport.rmi.RemoteServer;
import org.protege.editor.owl.server.transport.rmi.RmiServer;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.ServerDocument;

import java.net.URI;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Josef Hardi <johardi@stanford.edu> <br>
 *         Stanford Center for Biomedical Informatics Research
 */
public class LocalClient implements Client {

    private AuthToken authToken;
    private String serverAddress;
    private int registryPort;

    private ProjectId projectId;
    private UserId userId;

    private UserInfo userInfo; // cache
    private RemoteServer server;

    public LocalClient(AuthToken authToken, String serverAddress, int registryPort) {
        this.authToken = authToken;
        this.serverAddress = serverAddress;
        this.registryPort = registryPort;
        userId = authToken.getUser().getId();
    }

    @Override
    public void setActiveProject(ProjectId projectId) {
        this.projectId = projectId;
    }

    protected ProjectId getActiveProject() throws SynchronizationException {
        if (projectId != null) {
            return projectId;
        }
        throw new SynchronizationException("The current active ontology does not link to the server");
    }

    @Override
    public AuthToken getAuthToken() {
        return authToken;
    }

    @Override
    public UserInfo getUserInfo() {
        if (userInfo == null) {
            User user = authToken.getUser();
            userInfo = new UserInfo(user.getId().get(), user.getName().get(), user.getEmailAddress().get());
        }
        return userInfo;
    }

    @Override
    public List<Project> getProjects() throws ClientRequestException {
        try {
            return getProjects(userId);
        }
        catch (AuthorizationException | RemoteException e) {
            throw new ClientRequestException(e.getMessage(), e);
        }
    }

    @Override
    public List<Role> getActiveRoles() throws ClientRequestException {
        try {
            return getRoles(userId, getActiveProject());
        }
        catch (AuthorizationException | RemoteException | SynchronizationException e) {
            throw new ClientRequestException(e.getMessage(), e);
        }
    }

    @Override
    public List<Operation> getActiveOperations() throws ClientRequestException {
        try {
            return getOperations(userId, getActiveProject());
        }
        catch (AuthorizationException | RemoteException | SynchronizationException e) {
            throw new ClientRequestException(e.getMessage(), e);
        }
    }

    protected void connect() throws RemoteException {
        if (server == null) {
            server = (RemoteServer) ServerUtils.getRemoteService(serverAddress, registryPort, RmiServer.SERVER_SERVICE);
        }
    }

    public void disconnect() {
        server = null;
    }

    @Override
    public void createUser(User newUser, Optional<SaltedPasswordDigest> password) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            SaltedPasswordDigest passwordValue = (password.isPresent()) ? password.get() : null;
            server.createUser(authToken, newUser, passwordValue);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void deleteUser(UserId userId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.deleteUser(authToken, userId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void updateUser(UserId userId, User updatedUser) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.updateUser(authToken, userId, updatedUser);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public ServerDocument createProject(ProjectId projectId, Name projectName, Description description,
            UserId owner, Optional<ProjectOptions> options, Optional<CommitBundle> initialCommit)
                    throws AuthorizationException, ClientRequestException, RemoteException {
        ServerDocument serverDocument = null;
        try {
            connect();
            ProjectOptions projectOptions = (options.isPresent()) ? options.get() : null;
            serverDocument = server.createProject(authToken, projectId, projectName, description, owner, projectOptions);
        }
        catch (ServerServiceException e) {
            Throwable t = e.getCause();
            if (t instanceof IdAlreadyInUseException) {
                throw new ClientRequestException("Project ID is already used. Please use different name.", t);
            }
            else {
                try {
                    server.deleteProject(authToken, projectId, true);
                }
                catch (ServerServiceException ee) {
                    throw new ClientRequestException("Failed to create a new project. Please try again.", ee.getCause());
                }
                throw new ClientRequestException("Failed to create a new project. Please try again.", e.getCause());
            }
        }
        
        // Do initial commit if the commit bundle is not empty
        if (initialCommit.isPresent()) {
            try {
                server.commit(authToken, projectId, initialCommit.get());
            }
            catch (ServerServiceException | OutOfSyncException e) {
                try {
                    server.deleteProject(authToken, projectId, true);
                }
                catch (ServerServiceException ee) {
                    throw new ClientRequestException("Failed to create a new project. Please try again.", ee.getCause());
                }
                throw new ClientRequestException("Failed to create a new project. Please try again.", e.getCause());
            }
        }
        return serverDocument;
    }

    @Override
    public void deleteProject(ProjectId projectId, boolean includeFile) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.deleteProject(authToken, projectId, includeFile);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void updateProject(ProjectId projectId, Project updatedProject) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.updateProject(authToken, projectId, updatedProject);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public ServerDocument openProject(ProjectId projectId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            ServerDocument serverDocument = server.openProject(authToken, projectId);
            return serverDocument;
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void createRole(Role newRole) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.createRole(authToken, newRole);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void deleteRole(RoleId roleId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.deleteRole(authToken, roleId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void updateRole(RoleId roleId, Role updatedRole) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.updateRole(authToken, roleId, updatedRole);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void createOperation(Operation operation) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.createOperation(authToken, operation);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void deleteOperation(OperationId operationId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.deleteOperation(authToken, operationId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void updateOperation(OperationId operationId, Operation updatedOperation) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.updateOperation(authToken, operationId, updatedOperation);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void assignRole(UserId userId, ProjectId projectId, RoleId roleId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.assignRole(authToken, userId, projectId, roleId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void retractRole(UserId userId, ProjectId projectId, RoleId roleId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.retractRole(authToken, userId, projectId, roleId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public Host getHost() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getHost(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public void setHostAddress(URI hostAddress) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.setHostAddress(authToken, hostAddress);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public void setSecondaryPort(int portNumber) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.setSecondaryPort(authToken, portNumber);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public String getRootDirectory() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getRootDirectory(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public void setRootDirectory(String rootDirectory) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.setRootDirectory(authToken, rootDirectory);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public Map<String, String> getServerProperties() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getServerProperties(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public void setServerProperty(String property, String value) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.setServerProperty(authToken, property, value);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public void unsetServerProperty(String property) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            server.unsetServerProperty(authToken, property);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public ChangeHistory commit(ProjectId projectId, CommitBundle commitBundle)
            throws AuthorizationException, OutOfSyncException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.commit(authToken, projectId, commitBundle);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<User> getAllUsers() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getAllUsers(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public List<Project> getProjects(UserId userId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getProjects(authToken, userId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Project> getAllProjects() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getAllProjects(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public Map<ProjectId, List<Role>> getRoles(UserId userId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getRoles(authToken, userId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Role> getRoles(UserId userId, ProjectId projectId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getRoles(authToken, userId, projectId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Role> getAllRoles() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getAllRoles(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    @Override
    public Map<ProjectId, List<Operation>> getOperations(UserId userId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getOperations(authToken, userId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Operation> getOperations(UserId userId, ProjectId projectId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getOperations(authToken, userId, projectId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Operation> getOperations(RoleId roleId) throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getOperations(authToken, roleId);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Operation> getAllOperations() throws AuthorizationException, ClientRequestException, RemoteException {
        try {
            connect();
            return server.getAllOperations(authToken);
        }
        catch (ServerServiceException e) {
            throw new ClientRequestException(e.getMessage());
        }
    }

    /*
     * Utility methods for querying the client permissions
     */

    @Override
    public boolean canAddAxiom() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_AXIOM.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canRemoveAxiom() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_AXIOM.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canAddAnnotation() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_ONTOLOGY_ANNOTATION.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canRemoveAnnotation() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_ONTOLOGY_ANNOTATION.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canAddImport() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_IMPORT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canRemoveImport() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_IMPORT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canModifyOntologyId() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_ONTOLOGY_IRI.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canCreateUser() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_USER.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canDeleteUser() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_USER.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canUpdateUser() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_USER.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canCreateProject() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_PROJECT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canDeleteProject() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_PROJECT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canUpdateProject() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_PROJECT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canOpenProject() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.OPEN_PROJECT.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canCreateRole() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_ROLE.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canDeleteRole() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_ROLE.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canUpdateRole() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_ROLE.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canCreateOperation() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ADD_OPERATION.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canDeleteOperation() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.REMOVE_OPERATION.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canUpdateOperation() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_OPERATION.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canAssignRole() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.ASSIGN_ROLE.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canRetractRole() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.RETRACT_ROLE.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canStopServer() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.STOP_SERVER.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canUpdateServerConfig() {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, Operations.MODIFY_SERVER_SETTINGS.getId(), getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }

    @Override
    public boolean canPerformOperation(OperationId operationId) {
        boolean isAllowed = false;
        try {
            connect();
            isAllowed = server.isOperationAllowed(authToken, operationId, getActiveProject(), userId);
        }
        catch (AuthorizationException | ServerServiceException | RemoteException | SynchronizationException e) {
            // TODO Add logging
        }
        return isAllowed;
    }
}
