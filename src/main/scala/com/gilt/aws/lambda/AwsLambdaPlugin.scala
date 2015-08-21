package com.gilt.aws.lambda

import sbt._

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Unit]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Unit]("Package and deploy the current project to an existing AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := {
      val resolvedBucketId = resolveBucketId(s3Bucket.value)
      val resolvedLambdaName = resolveLambdaName(lambdaName.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      val result = AwsS3.pushJarToS3(jar, resolvedBucketId) match {
        case Success(s3Key) =>
          AwsLambda.updateLambda(resolvedLambdaName, resolvedBucketId, s3Key)
        case f: Failure =>
          f
      }

      result match {
        case s: Success[_] =>
          ()
        case f: Failure =>
          sys.error(s"Error updating lambda: ${f.exception.getLocalizedMessage}")
      }
    },
    createLambda := {
      val resolvedFunctionName = resolveLambdaName(lambdaName.value)
      val resolvedHandlerName = resolveHandlerName(handlerName.value)
      val resolvedRoleName = resolveRoleARN(roleArn.value)
      val resolvedBucketId = resolveBucketId(s3Bucket.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      AwsLambda.createLambda(jar, resolvedFunctionName, resolvedHandlerName, resolvedRoleName, resolvedBucketId) match {
        case s: Success[_] =>
          ()
        case f: Failure =>
          sys.error(s"Failed to create lambda function: ${f.exception.getLocalizedMessage}")
      }
    },
    s3Bucket := None,
    lambdaName := None,
    handlerName := None,
    roleArn := None
  )

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId = {
    sbtSettingValueOpt match {
      case Some(id) => S3BucketId(id)
      case None => sys.env.get(EnvironmentVariables.bucketId) match {
        case Some(envVarId) => S3BucketId(envVarId)
        case None => promptUserForS3BucketId()
      }
    }
  }

  private def resolveLambdaName(sbtSettingValueOpt: Option[String]): LambdaName = {
    sbtSettingValueOpt match {
      case Some(f) => LambdaName(f)
      case None => sys.env.get(EnvironmentVariables.lambdaName) match {
        case Some(envVarFunctionName) => LambdaName(envVarFunctionName)
        case None => promptUserForFunctionName()
      }
    }
  }

  private def resolveHandlerName(sbtSettingValueOpt: Option[String]): HandlerName = {
    sbtSettingValueOpt match {
      case Some(f) => HandlerName(f)
      case None => sys.env.get(EnvironmentVariables.handlerName) match {
        case Some(envVarFunctionName) => HandlerName(envVarFunctionName)
        case None => promptUserForHandlerName()
      }
    }
  }

  private def resolveRoleARN(sbtSettingValueOpt: Option[String]): RoleARN = {
    sbtSettingValueOpt match {
      case Some(f) => RoleARN(f)
      case None => sys.env.get(EnvironmentVariables.roleArn) match {
        case Some(envVarFunctionName) => RoleARN(envVarFunctionName)
        case None => promptUserForRoleARN()
      }
    }
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")
    val bucketId = S3BucketId(inputValue)

    AwsS3.getBucket(bucketId) match {
      case Some(_) =>
        bucketId
      case None =>
        val createBucket = readInput(s"Bucket $inputValue does not exist. Create it now? (y/n)")

        if(createBucket == "y") {
          AwsS3.createBucket(bucketId) match {
            case Success(createdBucketId) =>
              createdBucketId
            case f: Failure =>
              println(s"Failed to create S3 bucket: ${f.exception.getLocalizedMessage}")
              promptUserForS3BucketId()
          }
        }
        else promptUserForS3BucketId()
    }
  }

  private def promptUserForFunctionName(): LambdaName = {
    val inputValue = readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

    LambdaName(inputValue)
  }

  private def promptUserForHandlerName(): HandlerName = {
    val inputValue = readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

    HandlerName(inputValue)
  }

  private def promptUserForRoleARN(): RoleARN = {
    AwsIAM.basicLambdaRole() match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput(s"IAM role '${AwsIAM.BasicLambdaRoleName}' already exists. Reuse this role? (y/n)")
        
        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")
        
        if(createDefaultRole == "y") {
          AwsIAM.createBasicLambdaRole() match {
            case Success(createdRole) =>
              createdRole
            case f: Failure =>
              println(s"Failed to create role: ${f.exception.getLocalizedMessage}")
              promptUserForRoleARN()
          }
        } else readRoleARN()
    }
  }

  private def readRoleARN(): RoleARN = {
    val inputValue = readInput(s"Enter the ARN of the IAM role for the Lambda. (You also could have set the environment variable: ${EnvironmentVariables.roleArn} or the sbt setting: roleArn)")
    RoleARN(inputValue)
  }

  private def readInput(prompt: String): String = {
    SimpleReader.readLine(s"$prompt\n") match {
      case Some(f) =>
        f
      case None =>
        val badInputMessage = "Unable to read input"

        val updatedPrompt = if(prompt.startsWith(badInputMessage)) prompt else s"$badInputMessage\n$prompt"

        readInput(updatedPrompt)
    }
  }
}
