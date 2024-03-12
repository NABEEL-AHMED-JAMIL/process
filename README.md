### Scheduler Process API
Open-source API is used to run the scheduler with dynamic task. Create your pipeline and run with 'Scheduler Process'. Scheduler Process using in build postgres and kafka. <br>
To run docker file on cloud cluster its recommend you run service on single node to host service 'scheduler process'.

#### 1. Access Scheduler Process 
For Api : [Swagger Api](http://localhost:9098/api/v2/swagger-ui.html)<br>
For Web Portal : [Web Port](http://localhost:9098/api/v2/portal/)

#### 2. Current Active Profile & Branch
```
Note:- Current Image setup with 'stage' Profile & Branch 'process-v2'.
```

#### 3. Pre Setup
Before you run the application on you local server or docker need few environment set up.
<br>
If you are using on your local server please set up your environment variable
1.  ``` "export EFS_FILE_DIRE=G://efs" (Note:- create folder where you want in directory)```
2. ``` "export DATA_SOURCE_URL=localhost:5432" (Note:- Datasource url for your database) ```
3. ``` "export DATA_BASE=batch_process_v3" (Note:- Database name for your database) ```
4. ``` "export DATA_SOURCE_USERNAME=postgres" (Note:- Database username) ```
5. ``` "export DATA_SOURCE_PASSWORD=admin" (Note:- Database password) ```
6. ``` "export EMAIL_HOST=sandbox.smtp.mailtrap.io" (Note:- Email host) ```
7. ``` "export EMAIL_USERNAME=f81d02c4511f90" (Note:- Email username) ```
8. ``` "export EMAIL_PASSWORD=16b35d6d896ec0" (Note:- Email password) ```
9. ``` "export JWT_SECRET=bezKoderSecretKey" (Note:- Jwt secert) ```
10. ``` "export JWT_EXPIRATION=86400000" (Note:- Jwt expiration) ```
11. ``` "export JWT_REFRESH_EXPIRATION=86400000" (Note:- Jwt refresh expiration) ```

#### 4. Contact For Query 
You can contact us. For any query related this project below email
1. Email :- [Nabeel Ahmed Jamil](nabeel.amd93@gmail.com)
2. Source Code :- [Git Hub Process Repo](https://github.com/NABEEL-AHMED-JAMI)